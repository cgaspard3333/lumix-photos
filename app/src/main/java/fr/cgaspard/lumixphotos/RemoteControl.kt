package fr.cgaspard.lumixphotos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.math.abs
import kotlin.math.ln

data class RemoteState(
    val active: Boolean = false,
    /** Passage en mode prise de vue en cours. */
    val starting: Boolean = false,
    val settings: List<CameraSetting> = emptyList(),
    /** Valeur actuelle par type de réglage (valeur, seconde valeur éventuelle). */
    val values: Map<String, Pair<String, String?>> = emptyMap(),
    /** Valeurs utilisables dans le mode actuel de l'appareil (molette, mode photo/vidéo…). */
    val enabled: Set<String> = emptySet(),
    /** Valeurs dont curmenu indique la disponibilité ; ouverture et vitesse dépendent de [mode]. */
    val knownItems: Set<String> = emptySet(),
    /** Position de la molette, déduite des réglages disponibles. */
    val mode: ShootingMode? = null,
    /** Focales extrêmes en mm (équivalent 24×36) dans le mode actuel. */
    val zoomRange: IntRange? = null,
    val recording: Boolean = false,
    val recordSeconds: Int = 0,
    val photosLeft: Int? = null,
    val videoSecondsLeft: Int? = null,
    /** Un déclenchement est en cours : on évite d'empiler les commandes. */
    val shooting: Boolean = false,
    val message: String? = null,
) {
    fun isSelected(setting: CameraSetting, option: SettingOption): Boolean {
        val (value, value2) = values[setting.type] ?: return false
        if (setting.type in APEX_TYPES) return current(setting) == option
        return option.value == value && (option.value2 == null || value2 == null || option.value2 == value2)
    }

    fun isEnabled(option: SettingOption) = enabled.isEmpty() || option.itemId !in knownItems || option.itemId in enabled

    /** Le réglage peut-il être changé depuis l'appli dans la position actuelle de la molette ? */
    fun isEditable(setting: CameraSetting): Boolean = when (setting.id) {
        "aperture" -> mode?.apertureEditable ?: true
        "shutter" -> mode?.shutterEditable ?: true
        else -> setting.options.any { isEnabled(it) }
    }

    fun current(setting: CameraSetting): SettingOption? {
        if (setting.type in APEX_TYPES) {
            // Ouverture et vitesse : l'appareil donne une valeur exacte (ex. 761/256 pour f/2,8),
            // la liste propose les tiers d'IL : on retient le plus proche
            val apex = values[setting.type]?.first?.substringBefore('/')?.toIntOrNull() ?: return null
            return setting.options.minByOrNull { abs((it.value.substringBefore('/').toIntOrNull() ?: 0) - apex) }
        }
        return setting.options.firstOrNull { isSelected(setting, it) }
    }

    /** Libellé de la valeur actuelle, ou la valeur brute si elle n'est pas dans la liste du menu. */
    fun currentLabel(setting: CameraSetting): String =
        current(setting)?.label ?: values[setting.type]?.first?.replace('_', ' ') ?: "—"

    private companion object {
        val APEX_TYPES = setOf("focal", "shtrspeed")
    }
}

/**
 * Prise de vue à distance : visée en direct, déclenchement, vidéo, zoom et réglages.
 *
 * Partage avec le reste de l'appli le [http] à une seule place : l'appareil ne supporte pas
 * deux requêtes à la fois. La visée arrive à part, en UDP (~30 images/s en VGA).
 */
class RemoteController(
    private val scope: CoroutineScope,
    private val http: Semaphore,
    private val client: () -> LumixClient?,
    private val onBattery: (Battery) -> Unit,
) {
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    /** Dernière image de la visée, à part de [state] pour ne pas redessiner tout l'écran 30 fois par seconde. */
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    /** Focale actuelle en mm (équivalent 24×36), lue dans le flux de visée. */
    private val _focal = MutableStateFlow<Int?>(null)
    val focal: StateFlow<Int?> = _focal.asStateFlow()

    private var socket: DatagramSocket? = null
    private var streamJob: Job? = null
    private var pollJob: Job? = null
    private var startJob: Job? = null
    @Volatile private var lastFrameAt = 0L
    /** Orientation de l'appareil lue dans la visée (code EXIF), pour redresser l'image affichée. */
    @Volatile private var orientation = 1
    @Volatile private var streaming = false
    /** Le menu complet ne change pas pendant une session : lu une seule fois. */
    private var menu: List<CameraSetting>? = null

    fun start() {
        if (_state.value.active || _state.value.starting) return
        _state.value = RemoteState(starting = true)
        startJob = scope.launch(Dispatchers.IO) {
            val c = client() ?: return@launch
            try {
                http.withPermit { c.recMode() }
                // Le temps que l'objectif sorte et que l'appareil bascule
                delay(1_500)
                // Meilleure définition de visée (640×432 au lieu de 320×216) ; prise en compte au
                // démarrage de la visée seulement, d'où ce réglage avant startStream
                runCatching { http.withPermit { c.setSetting("liveviewsize", "vga") } }
                startStream()
                _state.update { it.copy(active = true, starting = false) }
                startPolling()
            } catch (e: Exception) {
                _state.value = RemoteState(message = "Impossible de passer en prise de vue : ${e.message}")
                return@launch
            }
            // Sans les réglages, la visée et le déclencheur restent utilisables
            try {
                loadSettings(c)
            } catch (e: Exception) {
                _state.update { it.copy(message = "Réglages de l'appareil illisibles : ${e.message}") }
            }
        }
    }

    /** Arrête la visée et repasse l'appareil en lecture (pour lister et télécharger les fichiers). */
    suspend fun stop() {
        startJob?.cancel()
        pollJob?.cancel()
        streaming = false
        streamJob?.cancel()
        socket?.close()
        socket = null
        val c = client()
        if (c != null) {
            withContext(Dispatchers.IO) {
                runCatching { http.withPermit { c.stopStream() } }
                if (_state.value.recording) runCatching { http.withPermit { c.stopVideo() } }
                // L'appareil peut ignorer « playmode » tant qu'il range l'objectif : on vérifie et on insiste
                repeat(10) {
                    runCatching { http.withPermit { c.playMode() } }
                    delay(800)
                    val state = runCatching { http.withPermit { c.keepAlive() } }.getOrDefault("")
                    if ("<cammode>play</cammode>" in state) return@withContext
                }
            }
        }
        _state.value = RemoteState()
        _frame.value = null
        _focal.value = null
    }

    /** Connexion perdue : on oublie la session sans rien envoyer à l'appareil. */
    fun reset() {
        startJob?.cancel()
        pollJob?.cancel()
        zoomJob?.cancel()
        streaming = false
        streamJob?.cancel()
        socket?.close()
        socket = null
        _state.value = RemoteState()
        _frame.value = null
        _focal.value = null
        _zoomTarget.value = null
    }

    /** Visée suspendue quand l'appli passe en arrière-plan, reprise au retour. */
    fun pauseStream() = stopStream()

    fun resumeStream() {
        if (_state.value.active && !streaming) scope.launch(Dispatchers.IO) { runCatching { startStream() } }
    }

    private suspend fun startStream() {
        val c = client() ?: return
        val s = socket ?: openSocket().also { socket = it }
        http.withPermit { c.startStream(s.localPort) }
        streaming = true
        lastFrameAt = SystemClock.elapsedRealtime()
        if (streamJob?.isActive != true) streamJob = scope.launch(Dispatchers.IO) { receive(s) }
    }

    private fun stopStream() {
        streaming = false
        streamJob?.cancel()
        socket?.close()
        socket = null
        val c = client() ?: return
        scope.launch(Dispatchers.IO) { http.withPermit { c.stopStream() } }
    }

    private fun openSocket(): DatagramSocket =
        // Port habituel des applis Lumix, sinon un port libre
        runCatching { DatagramSocket(PREFERRED_PORT) }.getOrElse { DatagramSocket(0) }.apply { soTimeout = 1_000 }

    /** Chaque paquet contient un en-tête Panasonic suivi d'une image JPEG complète. */
    private fun receive(s: DatagramSocket) {
        val buf = ByteArray(65_536)
        val packet = DatagramPacket(buf, buf.size)
        while (streaming && !s.isClosed) {
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            }
            val soi = (0 until packet.length - 1).firstOrNull { buf[it] == 0xFF.toByte() && buf[it + 1] == 0xD8.toByte() } ?: continue
            readFocal(buf, soi)
            // Orientation de l'appareil (code EXIF : 1 horizontal, 6 et 8 vertical, 3 retourné)
            if (soi > ORIENTATION_OFFSET) orientation = buf[ORIENTATION_OFFSET].toInt() and 0xFF
            val bitmap = BitmapFactory.decodeByteArray(buf, soi, packet.length - soi) ?: continue
            _frame.value = bitmap.rotatedFor(orientation).asImageBitmap()
            lastFrameAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Focale actuelle (mm, équivalent 24×36) : dans l'en-tête Panasonic qui précède chaque image,
     * toujours à 78 octets de sa fin (l'en-tête s'allonge quand des zones de mise au point s'y
     * ajoutent). Relevé sur un FZ1000 : 25 → 400 mm en photo, 37 → 592 mm en vidéo.
     */
    private fun readFocal(buf: ByteArray, headerLength: Int) {
        if (headerLength < FOCAL_FROM_END) return
        val i = headerLength - FOCAL_FROM_END
        val focal = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
        val range = _state.value.zoomRange
        // Garde-fou si un autre modèle range ses données autrement
        if (range != null && focal !in (range.first * 8 / 10)..(range.last * 12 / 10)) return
        if (focal != _focal.value) _focal.value = focal
    }

    /**
     * État de l'appareil (enregistrement, autonomie, batterie), toutes les secondes pendant une
     * vidéo et toutes les 3 s sinon. Sert aussi de maintien de session, et relance la visée si
     * l'appareil a cessé d'envoyer des images.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(if (_state.value.recording) 1_000 else 3_000)
                val c = client() ?: break
                val reply = runCatching { http.withPermit { c.keepAlive() } }.getOrNull() ?: continue
                fun tag(name: String) = Regex("<$name>([^<]*)</$name>").find(reply)?.groupValues?.get(1)
                _state.update {
                    it.copy(
                        recording = tag("rec") == "on",
                        recordSeconds = tag("progress_time")?.toIntOrNull() ?: 0,
                        photosLeft = tag("remaincapacity")?.toIntOrNull(),
                        videoSecondsLeft = tag("video_remaincapacity")?.toIntOrNull(),
                    )
                }
                Regex("<batt>(\\d+)/(\\d+)</batt>").find(reply)?.destructured?.let { (l, m) -> onBattery(Battery(l.toInt(), m.toInt())) }
                // La molette ne fait l'objet d'aucune notification : on relit les réglages disponibles
                if (!_state.value.recording && _state.value.settings.isNotEmpty()) checkModeChange(c)
                if (streaming && SystemClock.elapsedRealtime() - lastFrameAt > 2_500) runCatching { startStream() }
            }
        }
    }

    private suspend fun loadSettings(c: LumixClient) {
        val settings = lensSettings(c) + (menu ?: CameraMenu.parse(http.withPermit { c.info("allmenu") }).also { menu = it })
        val known = settings.filterNot { it.id == "aperture" || it.id == "shutter" }.flatMap { s -> s.options.map { it.itemId } }.toSet()
        _state.update { it.copy(settings = settings, knownItems = known) }
        refreshValues(c, settings.map { it.type }.distinct())
    }

    /** Ouverture et vitesse : leurs bornes changent avec le mode (vidéo, M…) et le zoom. */
    private suspend fun lensSettings(c: LumixClient): List<CameraSetting> {
        val lens = http.withPermit { c.info("lens") }
        CameraMenu.focalRange(lens)?.let { range -> _state.update { it.copy(zoomRange = range) } }
        val limits = CameraMenu.lensLimits(lens) ?: return emptyList()
        return listOf(CameraMenu.apertureSetting(limits), CameraMenu.shutterSetting(limits))
    }

    /** Si la molette a tourné : nouvelles bornes, nouvelles valeurs, réglages (in)disponibles mis à jour. */
    private suspend fun checkModeChange(c: LumixClient) {
        val enabled = runCatching { CameraMenu.enabledItems(http.withPermit { c.info("curmenu") }) }.getOrNull() ?: return
        if (enabled == _state.value.enabled) return
        val mode = ShootingMode.detect(enabled)
        val lens = runCatching { lensSettings(c) }.getOrDefault(emptyList())
        _state.update { s ->
            s.copy(
                enabled = enabled,
                mode = mode,
                settings = if (lens.isEmpty()) s.settings else lens + s.settings.filterNot { it.id == "aperture" || it.id == "shutter" },
            )
        }
        // L'appareil change lui-même certaines valeurs selon le mode (ISO auto en iA…)
        refreshValues(c, _state.value.settings.map { it.type }.distinct())
    }

    /** Relit la disponibilité des valeurs et la valeur actuelle des réglages [types]. */
    private suspend fun refreshValues(c: LumixClient, types: List<String>) {
        val enabled = runCatching { CameraMenu.enabledItems(http.withPermit { c.info("curmenu") }) }.getOrNull()
        val values = HashMap<String, Pair<String, String?>>()
        for (type in types) {
            val reply = runCatching { http.withPermit { c.getSetting(type) } }.getOrNull() ?: continue
            runCatching { CameraMenu.currentValue(reply, type) }.getOrNull()?.let { values[type] = it }
        }
        _state.update { s ->
            s.copy(
                values = s.values + values,
                enabled = enabled ?: s.enabled,
                mode = enabled?.let { ShootingMode.detect(it) } ?: s.mode,
            )
        }
    }

    fun capture() = command(shooting = true) { it.capture() }

    fun toggleVideo() {
        val recording = _state.value.recording
        command(shooting = true) { if (recording) it.stopVideo() else it.startVideo() }
        _state.update { it.copy(recording = !recording, recordSeconds = 0) }
    }

    fun zoom(tele: Boolean, fast: Boolean) = command {
        it.zoom((if (tele) "tele" else "wide") + if (fast) "-fast" else "-normal")
    }

    fun zoomStop() = command { it.zoomStop() }

    /** Focale visée par un pincement sur la visée, affichée sur la jauge (null hors geste). */
    private val _zoomTarget = MutableStateFlow<Int?>(null)
    val zoomTarget: StateFlow<Int?> = _zoomTarget.asStateFlow()
    private var zoomJob: Job? = null

    /**
     * Zoom au pincement. L'appareil ne sait pas aller à une focale donnée, seulement zoomer,
     * dézoomer (lent ou rapide) et s'arrêter : on l'asservit sur la focale lue dans la visée.
     */
    fun zoomTo(target: Int) {
        val range = _state.value.zoomRange ?: return
        _zoomTarget.value = target.coerceIn(range)
        if (zoomJob?.isActive == true) return
        zoomJob = scope.launch(Dispatchers.IO) {
            val c = client() ?: return@launch
            var sent: String? = null
            val deadline = SystemClock.elapsedRealtime() + 15_000
            // Vitesse du zoom (log focale / s), pour anticiper l'inertie de l'objectif et le retard de la visée
            var lastLog = ln((_focal.value ?: return@launch).toDouble())
            var lastAt = SystemClock.elapsedRealtime()
            var speed = 0.0
            var settleUntil = 0L
            var corrections = 0
            var moveStartedAt = 0L
            try {
                while (isActive && SystemClock.elapsedRealtime() < deadline) {
                    val goal = _zoomTarget.value ?: break
                    val focal = _focal.value ?: break
                    val now = SystemClock.elapsedRealtime()
                    val log = ln(focal.toDouble())
                    if (log != lastLog && now > lastAt) {
                        speed = speed * 0.5 + (log - lastLog) / ((now - lastAt) / 1000.0) * 0.5
                        lastLog = log
                        lastAt = now
                    } else if (now - lastAt > 250) {
                        speed = 0.0
                    }
                    if (sent != null) {
                        // En mouvement : on coupe là où l'objectif s'arrêtera, compte tenu de son inertie
                        // Au démarrage, la vitesse mesurée est sous-estimée (objectif à l'arrêt, visée en retard) :
                        // on retient au moins la vitesse typique de l'allure demandée
                        val typical = if (sent!!.endsWith("fast")) FAST_SPEED else NORMAL_SPEED
                        val effective = (if (sent!!.startsWith("tele")) 1 else -1) * maxOf(kotlin.math.abs(speed), typical)
                        // Aux extrémités, pas de freinage : l'objectif s'arrête de lui-même en butée
                        val aim = when (goal) {
                            range.last -> goal * 1.3
                            range.first -> goal / 1.3
                            else -> goal.toDouble()
                        }
                        val predicted = ln(aim) - (log + effective * ZOOM_LEAD_S)
                        val overshooting = if (sent!!.startsWith("tele")) predicted < STOP_GAP else predicted > -STOP_GAP
                        // Focale figée alors qu'on zoome : l'objectif est en butée
                        val blocked = now - lastAt > 500 && now - moveStartedAt > 500
                        if (overshooting || blocked) {
                            runCatching { http.withPermit { c.zoomStop() } }
                            sent = null
                            settleUntil = now + SETTLE_MS
                        } else if (sent!!.endsWith("fast") && kotlin.math.abs(predicted) < FAST_GAP) {
                            // À l'approche : passage en vitesse lente
                            val slow = sent!!.replace("fast", "normal")
                            runCatching { http.withPermit { c.zoom(slow) } }
                            sent = slow
                        }
                    } else if (now >= settleUntil) {
                        // Immobile : on corrige seulement si l'écart reste visible
                        val gap = ln(goal.toDouble()) - log
                        if (kotlin.math.abs(gap) < SETTLED_GAP || (!pinching && corrections >= MAX_CORRECTIONS)) {
                            if (!pinching) break
                        } else {
                            val tele = gap > 0
                            val command = (if (tele) "tele" else "wide") + if (kotlin.math.abs(gap) > FAST_GAP) "-fast" else "-normal"
                            runCatching { http.withPermit { c.zoom(command) } }
                            sent = command
                            moveStartedAt = now
                            if (!pinching) corrections++
                        }
                    }
                    delay(30)
                }
            } finally {
                if (sent != null) runCatching { http.withPermit { c.zoomStop() } }
                if (!pinching) _zoomTarget.value = null
            }
        }
    }

    @Volatile private var pinching = false

    fun pinchStarted() { pinching = true }

    /** Fin du geste : l'objectif termine sa course vers la cible puis s'arrête. */
    fun pinchEnded() {
        pinching = false
        if (zoomJob?.isActive != true) _zoomTarget.value = null
    }

    /**
     * [u] et [v] entre 0 et 1 sur la visée telle qu'affichée ; l'appareil attend des coordonnées
     * dans le repère de son capteur, qu'on retrouve en annulant la rotation d'affichage.
     */
    fun focusAt(u: Float, v: Float) {
        val (x, y) = when (orientation) {
            6 -> v to 1 - u
            8 -> 1 - v to u
            3 -> 1 - u to 1 - v
            else -> u to v
        }
        command { it.touchFocus((x * 1000).toInt().coerceIn(0, 1000), (y * 1000).toInt().coerceIn(0, 1000)) }
    }

    fun set(setting: CameraSetting, option: SettingOption) {
        // Affichage immédiat, confirmé ensuite par l'appareil
        _state.update { it.copy(values = it.values + (setting.type to (option.value to option.value2))) }
        scope.launch(Dispatchers.IO) {
            val c = client() ?: return@launch
            try {
                http.withPermit { c.setSetting(setting.type, option.value, option.value2) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "${setting.title} : ${e.message}") }
            }
            // Un réglage peut en rendre d'autres disponibles ou non (format → taille d'image…)
            refreshValues(c, _state.value.settings.map { it.type }.distinct())
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun command(shooting: Boolean = false, block: (LumixClient) -> Unit) {
        val c = client() ?: return
        if (shooting) _state.update { it.copy(shooting = true) }
        scope.launch(Dispatchers.IO) {
            try {
                http.withPermit { block(c) }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message) }
            } finally {
                if (shooting) _state.update { it.copy(shooting = false) }
            }
        }
    }

    private fun Bitmap.rotatedFor(exifOrientation: Int): Bitmap {
        val degrees = when (exifOrientation) {
            6 -> 90f
            8 -> 270f
            3 -> 180f
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees) }, true)
    }

    private companion object {
        const val PREFERRED_PORT = 49199
        const val FOCAL_FROM_END = 78
        /** Position de l'orientation dans l'en-tête des images (avant les zones AF, donc fixe). */
        const val ORIENTATION_OFFSET = 42
        /** Temps (s) pendant lequel l'objectif continue après l'ordre d'arrêt, retard de la visée compris. */
        const val ZOOM_LEAD_S = 0.3
        /** Écarts en log de focale : arrêt anticipé à ±2 %, tolérance finale ±5 %, rapide au-delà de ±50 %. */
        const val STOP_GAP = 0.02
        const val SETTLED_GAP = 0.05
        const val FAST_GAP = 0.4
        /** Attente après un arrêt, le temps que l'objectif s'immobilise et que la visée le montre. */
        const val SETTLE_MS = 400L
        const val MAX_CORRECTIONS = 3
        /** Vitesses typiques du FZ1000 en log de focale par seconde (allure lente / rapide). */
        const val NORMAL_SPEED = 0.6
        const val FAST_SPEED = 1.0
    }
}
