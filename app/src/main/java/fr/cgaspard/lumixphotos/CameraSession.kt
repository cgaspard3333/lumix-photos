package fr.cgaspard.lumixphotos

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Working(val message: String) : ConnectionState
    data class Connected(val host: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

enum class ViewMode { GRID, DETAILS }

enum class DisplayFilter(val label: String) {
    ALL("Tout"),
    PHOTOS("Photos"),
    VIDEOS("Vidéos"),
    NOT_DOWNLOADED("À télécharger"),
}

/** Ce que l'utilisateur s'apprête à télécharger, pour la fenêtre de confirmation. */
data class DownloadPlan(
    val items: List<CameraItem>,
    /** Taille de l'original par fichier (-1 si encore inconnue). */
    val sizes: Map<String, Long>,
    /** Fichiers déjà présents dans la galerie du téléphone. */
    val alreadyDownloaded: Set<String>,
    val bytesPerSecond: Long,
    /** Espace libre sur le téléphone. */
    val freeBytes: Long,
) {
    fun items(skipExisting: Boolean) = if (skipExisting) items.filter { it.id !in alreadyDownloaded } else items

    fun bytes(skipExisting: Boolean, reduced: Boolean) = items(skipExisting).sumOf { item ->
        // Les aperçus réduits pèsent ~1,1 Mo ; les vidéos restent à leur taille d'origine
        if (reduced && item.canReduce) REDUCED_PHOTO_BYTES else sizes[item.id]?.coerceAtLeast(0) ?: 0
    }

    fun unknownSizes(skipExisting: Boolean) = items(skipExisting).count { (sizes[it.id] ?: -1) <= 0 }

    companion object {
        const val REDUCED_PHOTO_BYTES = 1_100_000L
        /** Marge laissée libre sur le téléphone. */
        const val FREE_SPACE_MARGIN = 300_000_000L
    }
}

data class DownloadProgress(
    val index: Int,
    val count: Int,
    val fileName: String,
    val bytes: Long = 0,
    val totalBytes: Long = -1,
    val doneBytes: Long = 0,
    val plannedBytes: Long = -1,
    val bytesPerSecond: Long = 0,
    /** Message affiché pendant une tentative de reprise après coupure. */
    val retrying: String? = null,
) {
    val fraction get() = if (totalBytes > 0) bytes.toFloat() / totalBytes else 0f
    val totalFraction get() = if (plannedBytes > 0) ((doneBytes + bytes).toFloat() / plannedBytes).coerceIn(0f, 1f) else fraction
}

/** Niveau de batterie rapporté par l'appareil, ex. « 2/3 ». */
data class Battery(val level: Int, val max: Int) {
    val low get() = max > 0 && level * 3 <= max
    override fun toString() = "$level/$max"
}

data class UiState(
    val hostInput: String = "",
    val wifiSsid: String = "",
    val wifiPassword: String = "",
    val connection: ConnectionState = ConnectionState.Disconnected,
    val battery: Battery? = null,
    val items: List<CameraItem> = emptyList(),
    /** Métadonnées connues, par nom de fichier. */
    val meta: Map<String, FileMeta> = emptyMap(),
    val selected: Set<String> = emptySet(),
    /** Hors de ce mode, toucher un fichier l'ouvre en grand ; dedans, le coche ou le décoche. */
    val selectionMode: Boolean = false,
    /** Copies présentes dans la galerie du téléphone, par nom de fichier. */
    val localCopies: Map<String, List<MediaSaver.LocalCopy>> = emptyMap(),
    val viewMode: ViewMode = ViewMode.GRID,
    val columns: Int = 3,
    val filter: DisplayFilter = DisplayFilter.ALL,
    val plan: DownloadPlan? = null,
    val download: DownloadProgress? = null,
    val message: String? = null,
) {
    val metaLoaded get() = items.count { it.title in meta }

    /** Copie du téléphone correspondant à ce fichier de l'appareil (même nom et même taille). */
    fun localCopy(item: CameraItem): MediaSaver.LocalCopy? = localCopies[item.title]
        ?.filter { MediaSaver.matches(it, meta[item.title]?.size ?: -1) }
        ?.minByOrNull { if (it.reduced) 1 else 0 }

    fun isDownloaded(item: CameraItem) = localCopy(item) != null

    val visibleItems: List<CameraItem>
        get() = when (filter) {
            DisplayFilter.ALL -> items
            DisplayFilter.PHOTOS -> items.filter { !it.isVideo }
            DisplayFilter.VIDEOS -> items.filter { it.isVideo }
            DisplayFilter.NOT_DOWNLOADED -> items.filter { !isDownloaded(it) }
        }
}

/**
 * Session avec l'appareil, rattachée à l'application et non à l'écran : un téléchargement
 * continue quand l'appli passe en arrière-plan (le [DownloadService] garde le processus en vie).
 */
class CameraSession(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("lumix", Context.MODE_PRIVATE)
    private val metaCache = MetaCache(app)
    private val _state = MutableStateFlow(
        UiState(
            hostInput = prefs.getString(KEY_HOST, "") ?: "",
            wifiSsid = prefs.getString(KEY_SSID, "") ?: "",
            wifiPassword = prefs.getString(KEY_WIFI_PASSWORD, "") ?: "",
            viewMode = runCatching { ViewMode.valueOf(prefs.getString(KEY_VIEW, null)!!) }.getOrDefault(ViewMode.GRID),
            columns = prefs.getInt(KEY_COLUMNS, 3),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var client: LumixClient? = null
    private var cameraWifi: ConnectivityManager.NetworkCallback? = null
    private var rejoinJob: Job? = null
    private var keepAliveJob: Job? = null
    private var metaJob: Job? = null
    private var downloadJob: Job? = null

    // Une seule requête à la fois : des connexions simultanées peuvent figer l'appareil,
    // et en paralléliser plusieurs n'augmente de toute façon pas le débit (~2 Mo/s au total)
    private val cameraHttp = Semaphore(1)
    private val thumbnails = LruCache<String, ImageBitmap>(400)
    private val previews = LruCache<String, ImageBitmap>(6)

    /** Prise de vue à distance : partage la même file de requêtes que le reste de l'appli. */
    val remote = RemoteController(scope, cameraHttp, { client }) { battery -> _state.update { it.copy(battery = battery) } }
    // Certains fichiers font bloquer le serveur de l'appareil : on ne les redemande jamais
    private val failedImages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        // Remplacé par la lecture de la galerie (voir refreshDownloaded)
        prefs.edit().remove("downloaded").apply()
        refreshDownloaded()
    }

    fun setHostInput(value: String) = _state.update { it.copy(hostInput = value.trim()) }

    fun setWifi(ssid: String, password: String) {
        prefs.edit().putString(KEY_SSID, ssid.trim()).putString(KEY_WIFI_PASSWORD, password).apply()
        _state.update { it.copy(wifiSsid = ssid.trim(), wifiPassword = password) }
    }

    fun connect() {
        if (_state.value.connection is ConnectionState.Working) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CameraFinder.bindToWifi(app)
                    val host = findHost()
                    working("Connexion à $host…")
                    val c = LumixClient(host)
                    val cameraState = c.connect(deviceName = "${Build.MANUFACTURER} ${Build.MODEL}", deviceId = deviceId())
                    client = c
                    prefs.edit().putString(KEY_HOST, host).apply()
                    _state.update { it.copy(hostInput = host, battery = parseBattery(cameraState)) }
                    // Mémorise le nom du Wi-Fi de l'appareil pour la connexion automatique suivante
                    val cameraName = c.friendlyName()
                    if (cameraName != null && _state.value.wifiSsid.isEmpty()) setWifi(cameraName, _state.value.wifiPassword)
                    working("Lecture de la carte SD…")
                    val items = c.listItems()
                    working("Identification de la carte SD…")
                    val meta = metaCache.selectCard(cameraName ?: host, items) { item ->
                        runCatching { c.fetchMeta(item.originalUrl) }.getOrNull()
                    }
                    _state.update {
                        it.copy(connection = ConnectionState.Connected(host), items = items, meta = meta, selected = emptySet())
                    }
                }
                startKeepAlive()
                startMetaLoader()
            } catch (e: Exception) {
                client = null
                releaseCameraWifi()
                _state.update { it.copy(connection = ConnectionState.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun refresh() {
        val c = client ?: return connect()
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) { cameraHttp.withPermit { c.listItems() } }
                _state.update { s ->
                    val ids = items.mapTo(HashSet()) { it.id }
                    s.copy(items = items, selected = s.selected.filterTo(mutableSetOf()) { it in ids })
                }
                startMetaLoader()
            } catch (e: Exception) {
                lostConnection(e)
            }
        }
        refreshDownloaded()
    }

    fun disconnect() {
        keepAliveJob?.cancel()
        metaJob?.cancel()
        downloadJob?.cancel()
        // Remet l'appareil en lecture si on était en prise de vue, avant de lâcher la connexion
        val c = client
        if (c != null && remote.state.value.let { it.active || it.starting }) scope.launch { remote.stop() }
        client = null
        rejoinJob?.cancel()
        releaseCameraWifi()
        _state.update {
            it.copy(
                connection = ConnectionState.Disconnected, items = emptyList(), selected = emptySet(),
                selectionMode = false, download = null, battery = null,
            )
        }
    }

    private suspend fun findHost(): String {
        val typed = _state.value.hostInput
        if (typed.isNotEmpty()) {
            working("Recherche de l'appareil sur $typed…")
            if (CameraFinder.isReachable(typed)) return typed
        }
        // Cas le plus courant : téléphone déjà connecté au point d'accès Wi-Fi de l'appareil
        working("Recherche de l'appareil en connexion directe…")
        if (CameraFinder.isReachable(DIRECT_MODE_IP)) return DIRECT_MODE_IP
        val ssid = _state.value.wifiSsid
        if (ssid.isNotEmpty()) {
            working("Connexion au Wi-Fi « $ssid »…\nAcceptez la demande d'Android si elle s'affiche.")
            val callback = CameraFinder.joinCameraWifi(app, ssid, _state.value.wifiPassword, ::onCameraWifiLost)
            if (callback != null) {
                cameraWifi = callback
                // Le temps que le téléphone obtienne son adresse sur le réseau de l'appareil
                repeat(10) {
                    if (CameraFinder.isReachable(DIRECT_MODE_IP)) return DIRECT_MODE_IP
                    delay(1_000)
                }
            }
        }
        working("Recherche de l'appareil sur le réseau…")
        CameraFinder.discover(app)?.let { return it }
        throw IllegalStateException(
            "Appareil introuvable. Sur l'appareil : Wi-Fi > Nouvelle connexion > " +
                "« Prise de vue à distance et affichage », puis connectez le téléphone au Wi-Fi de l'appareil" +
                (if (ssid.isEmpty()) "." else ", ou vérifiez le mot de passe Wi-Fi ci-dessous.")
        )
    }

    private fun releaseCameraWifi() {
        cameraWifi?.let { CameraFinder.releaseCameraWifi(app, it) }
        cameraWifi = null
    }

    /**
     * Le Wi-Fi de l'appareil a disparu (coupure, téléphone reparti sur la box…) : Android ne le
     * rejoint pas de lui-même, on le redemande jusqu'à ce qu'il revienne. La demande ayant déjà été
     * acceptée une fois, Android reconnecte sans redemander. Un téléchargement en cours reprend
     * ensuite là où il s'était arrêté.
     */
    private fun onCameraWifiLost() {
        if (client == null || rejoinJob?.isActive == true) return
        rejoinJob = scope.launch(Dispatchers.IO) {
            releaseCameraWifi()
            val ssid = _state.value.wifiSsid
            while (isActive && client != null) {
                delay(3_000)
                val callback = CameraFinder.joinCameraWifi(app, ssid, _state.value.wifiPassword, ::onCameraWifiLost)
                if (callback != null) {
                    cameraWifi = callback
                    break
                }
            }
        }
    }

    private fun working(message: String) = _state.update { it.copy(connection = ConnectionState.Working(message)) }

    private fun deviceId(): String =
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().uppercase().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    private fun parseBattery(cameraState: String): Battery? =
        Regex("<batt>(\\d+)/(\\d+)</batt>").find(cameraState)?.destructured?.let { (level, max) ->
            Battery(level.toInt(), max.toInt())
        }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                delay(10_000)
                val c = client ?: break
                // Pendant un téléchargement, le trafic suffit à garder la session ouverte ;
                // en prise de vue, la télécommande interroge déjà l'appareil
                if (downloadJob?.isActive == true || remote.state.value.let { it.active || it.starting }) continue
                try {
                    val cameraState = cameraHttp.withPermit { c.keepAlive() }
                    parseBattery(cameraState)?.let { b -> _state.update { it.copy(battery = b) } }
                    failures = 0
                } catch (e: Exception) {
                    if (++failures >= 3) {
                        lostConnection(e)
                        break
                    }
                }
            }
        }
    }

    /**
     * La liste DLNA ne donne ni date ni taille : on les lit fichier par fichier (requête HEAD),
     * en arrière-plan, en laissant passer les vignettes entre deux requêtes.
     */
    private fun startMetaLoader() {
        metaJob?.cancel()
        metaJob = scope.launch(Dispatchers.IO) {
            val missing = _state.value.items.filter { it.title !in _state.value.meta }
            val batch = HashMap<String, FileMeta>()
            var lastFlush = SystemClock.elapsedRealtime()
            var failures = 0
            var loaded = 0
            fun flush() {
                if (batch.isEmpty()) return
                val copy = HashMap(batch)
                batch.clear()
                _state.update { it.copy(meta = it.meta + copy) }
                lastFlush = SystemClock.elapsedRealtime()
            }
            for (item in missing) {
                val c = client ?: break
                try {
                    batch[item.title] = cameraHttp.withPermit { c.fetchMeta(item.originalUrl) }
                    failures = 0
                } catch (_: Exception) {
                    if (++failures >= 5) break
                }
                if (batch.size >= 50 || SystemClock.elapsedRealtime() - lastFlush > 1_000) flush()
                if (++loaded % 200 == 0) metaCache.save(_state.value.meta)
            }
            flush()
            metaCache.save(_state.value.meta)
        }
    }

    private fun lostConnection(e: Exception) {
        keepAliveJob?.cancel()
        metaJob?.cancel()
        client = null
        _state.update {
            it.copy(connection = ConnectionState.Failed("Connexion perdue : ${e.message}"), download = null)
        }
    }

    /** Relit la galerie du téléphone : à appeler au retour dans l'appli (photos effacées entre-temps…). */
    fun refreshDownloaded() {
        scope.launch(Dispatchers.IO) {
            val copies = MediaSaver.localCopies(app)
            _state.update { it.copy(localCopies = copies) }
        }
    }

    // Vignettes 4:3 de l'appareil : les photos 3:2 y sont entourées de bandes noires, qu'on retire
    suspend fun thumbnail(item: CameraItem): ImageBitmap? = loadImage(item.thumbnailUrl, thumbnails, timeoutMs = 5_000, trim = true)

    /** Aperçu 1620×1080 pour l'affichage plein écran (~1 Mo, moins d'une seconde). */
    suspend fun preview(item: CameraItem): ImageBitmap? = loadImage(item.previewUrl, previews, timeoutMs = 15_000)

    private suspend fun loadImage(url: String?, cache: LruCache<String, ImageBitmap>, timeoutMs: Int, trim: Boolean = false): ImageBitmap? {
        if (url == null) return null
        cache.get(url)?.let { return it }
        if (url in failedImages) return null
        val c = client ?: return null
        return withContext(Dispatchers.IO) {
            cameraHttp.withPermit {
                // Re-vérifié sous le verrou : la cellule a pu être recomposée pendant l'attente
                cache.get(url)?.let { return@withPermit it }
                if (url in failedImages) return@withPermit null
                runCatching {
                    val response = c.openStream(url, timeoutMs = timeoutMs, reuseConnection = true)
                    val bitmap = response.stream.use { BitmapFactory.decodeStream(it) }!!
                    (if (trim) bitmap.trimBlackBars() else bitmap).rotated(response.meta.rotation).asImageBitmap()
                }.onSuccess { cache.put(url, it) }
                    .onFailure { failedImages += url }
                    .getOrNull()
            }
        }
    }

    fun enterSelection() = _state.update { it.copy(selectionMode = true) }

    fun exitSelection() = _state.update { it.copy(selectionMode = false, selected = emptySet()) }

    /** Coche ou décoche un fichier (et passe en mode sélection, ex. depuis un appui long). */
    fun toggle(item: CameraItem) = _state.update {
        it.copy(selected = if (item.id in it.selected) it.selected - item.id else it.selected + item.id, selectionMode = true)
    }

    /** Sélectionne / désélectionne tous les fichiers d'un jour. */
    fun toggleDay(items: List<CameraItem>) = _state.update { s ->
        val ids = items.map { it.id }
        s.copy(selected = if (s.selected.containsAll(ids)) s.selected - ids.toSet() else s.selected + ids, selectionMode = true)
    }

    /** Sélection par glissement : [base] est la sélection au début du geste. */
    fun dragSelect(base: Set<String>, range: List<CameraItem>, select: Boolean) = _state.update { s ->
        val ids = range.map { it.id }
        s.copy(selected = if (select) base + ids else base - ids.toSet(), selectionMode = true)
    }

    // Les sélections rapides portent sur ce qui est affiché (filtre compris)
    fun selectAll() = select { true }
    fun selectPhotos() = select { !it.isVideo }
    fun selectVideos() = select { it.isVideo }
    fun selectNew() = select { !_state.value.isDownloaded(it) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    private fun select(predicate: (CameraItem) -> Boolean) = _state.update { s ->
        s.copy(selected = s.visibleItems.filter(predicate).mapTo(mutableSetOf()) { it.id }, selectionMode = true)
    }

    fun setViewMode(mode: ViewMode) {
        prefs.edit().putString(KEY_VIEW, mode.name).apply()
        _state.update { it.copy(viewMode = mode) }
    }

    fun setColumns(columns: Int) {
        val c = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        prefs.edit().putInt(KEY_COLUMNS, c).apply()
        _state.update { it.copy(columns = c) }
    }

    fun setFilter(filter: DisplayFilter) = _state.update { it.copy(filter = filter) }

    fun prepareDownload() {
        val s = _state.value
        val items = s.items.filter { it.id in s.selected }
        if (items.isEmpty() || client == null) return
        _state.update {
            it.copy(
                plan = DownloadPlan(
                    items = items,
                    sizes = items.associate { item -> item.id to (s.meta[item.title]?.size ?: -1L) },
                    alreadyDownloaded = items.filter { item -> s.isDownloaded(item) }.mapTo(HashSet()) { item -> item.id },
                    bytesPerSecond = prefs.getLong(KEY_SPEED, DEFAULT_SPEED),
                    freeBytes = StatFs(Environment.getExternalStorageDirectory().path).availableBytes,
                )
            )
        }
    }

    fun dismissPlan() = _state.update { it.copy(plan = null) }

    fun startDownload(reducedPhotos: Boolean, skipExisting: Boolean) {
        val c = client ?: return
        val plan = _state.value.plan ?: return
        if (downloadJob?.isActive == true) return
        val items = plan.items(skipExisting)
        if (items.isEmpty()) return dismissPlan()
        val planned = plan.bytes(skipExisting, reducedPhotos)
        // Renseigné tout de suite : le service s'arrête dès qu'il ne voit plus de téléchargement
        _state.update {
            it.copy(plan = null, download = DownloadProgress(1, items.size, items.first().title, plannedBytes = planned))
        }
        downloadJob = scope.launch(Dispatchers.IO) {
            var ok = 0
            var doneBytes = 0L
            val started = SystemClock.elapsedRealtime()
            val errors = mutableListOf<String>()
            items.forEachIndexed { index, item ->
                val reduced = reducedPhotos && item.canReduce
                val url = if (reduced) item.previewUrl!! else item.originalUrl
                val name = if (reduced) item.title.replace(".", "${MediaSaver.REDUCED_SUFFIX}.") else item.title
                _state.update {
                    it.copy(download = DownloadProgress(index + 1, items.size, name, doneBytes = doneBytes, plannedBytes = planned))
                }
                try {
                    // Taille, date et orientation : déjà connues pour un original, sinon une requête HEAD
                    val known = _state.value.meta[item.title]
                    val meta = known?.takeIf { !reduced && it.size > 0 }
                        ?: retrying { cameraHttp.withPermit { c.fetchMeta(url) } }
                    val length = meta.size
                    if (length <= 0) throw IOException("taille inconnue")
                    _state.update { s -> s.copy(download = s.download?.copy(totalBytes = length)) }
                    val uri = MediaSaver.save(app, item, name, takenAt = known?.date ?: meta.date, rotation = meta.rotation) { out ->
                        downloadInChunks(c, url, length, out) { bytes ->
                            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                            _state.update { s ->
                                s.copy(download = s.download?.copy(bytes = bytes, bytesPerSecond = (doneBytes + bytes) * 1000 / elapsed))
                            }
                        }
                    }
                    doneBytes += length
                    ok++
                    _state.update { s ->
                        val copies = s.localCopies[item.title].orEmpty() + MediaSaver.LocalCopy(uri, length, reduced)
                        s.copy(localCopies = s.localCopies + (item.title to copies), selected = s.selected - item.id)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${item.title} : ${e.message}"
                }
            }
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000
            // Débit mesuré, moyenné avec les précédents : une session perturbée ne fausse pas tout
            if (doneBytes > 20_000_000 && seconds > 0) {
                val previous = prefs.getLong(KEY_SPEED, DEFAULT_SPEED)
                prefs.edit().putLong(KEY_SPEED, (previous + doneBytes / seconds) / 2).apply()
            }
            val msg = buildString {
                append("$ok fichier(s) enregistré(s) dans DCIM/Lumix")
                append("\n${formatSize(doneBytes)} en ${formatDuration(seconds)}")
                if (errors.isNotEmpty()) append("\n${errors.size} échec(s) : ${errors.first()}")
            }
            // Tout est téléchargé : on quitte le mode sélection
            _state.update { it.copy(download = null, message = msg, selectionMode = it.selected.isNotEmpty()) }
            refreshDownloaded()
        }
        // Garde le processus en vie (et le Wi-Fi actif) si l'appli passe en arrière-plan
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
    }

    /**
     * Télécharge par morceaux de [CHUNK_BYTES] (requêtes Range) : entre deux morceaux, l'appareil
     * redevient disponible pour les vignettes, qui sinon resteraient bloquées pendant toute une
     * vidéo 4K. Après une coupure, on reprend au dernier octet reçu.
     */
    private suspend fun downloadInChunks(c: LumixClient, url: String, length: Long, out: OutputStream, onProgress: (Long) -> Unit) {
        var offset = 0L
        var reported = 0L
        val buf = ByteArray(64 * 1024)
        while (offset < length) {
            retrying {
                cameraHttp.withPermit {
                    val response = c.openStream(url, timeoutMs = 30_000, reuseConnection = true, offset = offset, length = minOf(CHUNK_BYTES, length - offset))
                    response.stream.use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            offset += n
                            // Une mise à jour par Mo ; la lecture réseau étant bloquante, l'annulation se vérifie ici
                            if (offset - reported >= 1_000_000) {
                                reported = offset
                                currentCoroutineContext().ensureActive()
                                onProgress(offset)
                            }
                        }
                    }
                }
            }
        }
        onProgress(offset)
        if (offset != length) throw IOException("Fichier incomplet : $offset / $length octets")
    }

    /**
     * Réessaie [block] pendant ~3 min en cas d'erreur réseau (coupure Wi-Fi, appareil
     * momentanément injoignable), en se reliant à chaque fois au Wi-Fi courant.
     */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block().also {
                    if (attempt > 0) _state.update { s -> s.copy(download = s.download?.copy(retrying = null)) }
                }
            } catch (e: IOException) {
                if (++attempt > RETRY_DELAYS_S.size) throw e
                val wait = RETRY_DELAYS_S[attempt - 1]
                _state.update { s ->
                    s.copy(download = s.download?.copy(retrying = "Connexion interrompue, nouvelle tentative $attempt/${RETRY_DELAYS_S.size} dans $wait s…"))
                }
                delay(wait * 1_000L)
                // Avec la connexion automatique, le callback réseau se charge de la reliaison
                if (cameraWifi == null && rejoinJob?.isActive != true) CameraFinder.bindToWifi(app)
            }
        }
    }

    /** Passe en prise de vue à distance (impossible pendant un téléchargement : l'appareil doit rester en lecture). */
    fun enterRemote() {
        if (client == null || downloadJob?.isActive == true) return
        metaJob?.cancel()
        remote.start()
    }

    /** Revient à la galerie ; les photos et vidéos prises entre-temps apparaissent. */
    fun exitRemote() {
        scope.launch {
            remote.stop()
            // Juste après le retour en lecture, l'appareil peut encore refuser la liste : on réessaie un peu
            val c = client ?: return@launch
            var listed: List<CameraItem>? = null
            for (attempt in 1..8) {
                listed = runCatching { withContext(Dispatchers.IO) { cameraHttp.withPermit { c.listItems() } } }.getOrNull()
                if (listed != null) break
                delay(1_500)
            }
            val items = listed ?: return@launch lostConnection(IOException("l'appareil ne répond plus après la prise de vue"))
            _state.update { s ->
                val ids = items.mapTo(HashSet()) { it.id }
                s.copy(items = items, selected = s.selected.filterTo(mutableSetOf()) { it in ids })
            }
            startKeepAlive()
            startMetaLoader()
            refreshDownloaded()
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.update { it.copy(download = null, message = "Téléchargement annulé") }
        refreshDownloaded()
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Retire les bandes noires uniformes sur les bords (au plus 15 % de chaque côté). */
    private fun Bitmap.trimBlackBars(): Bitmap {
        fun dark(pixel: Int) = maxOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF) < 16
        fun rowDark(y: Int) = (0 until width step 4).all { dark(getPixel(it, y)) }
        fun columnDark(x: Int) = (0 until height step 4).all { dark(getPixel(x, it)) }
        val maxY = height * 15 / 100
        val maxX = width * 15 / 100
        val top = (0 until maxY).firstOrNull { !rowDark(it) } ?: 0
        val bottom = (0 until maxY).firstOrNull { !rowDark(height - 1 - it) } ?: 0
        val left = (0 until maxX).firstOrNull { !columnDark(it) } ?: 0
        val right = (0 until maxX).firstOrNull { !columnDark(width - 1 - it) } ?: 0
        if (top + bottom + left + right == 0) return this
        return Bitmap.createBitmap(this, left, top, width - left - right, height - top - bottom)
    }

    private fun Bitmap.rotated(exifOrientation: Int): Bitmap {
        val degrees = when (exifOrientation) {
            3 -> 180f
            6 -> 90f
            8 -> 270f
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees) }, true)
    }

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 8
        private const val KEY_HOST = "host"
        private const val KEY_SSID = "wifi_ssid"
        private const val KEY_WIFI_PASSWORD = "wifi_password"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_VIEW = "view_mode"
        private const val KEY_COLUMNS = "columns"
        private const val KEY_SPEED = "bytes_per_second"
        // Débit mesuré sur un FZ1000, connexion directe
        private const val DEFAULT_SPEED = 2_000_000L
        /** ~4 s de transfert : les vignettes n'attendent jamais plus longtemps. */
        private const val CHUNK_BYTES = 8L * 1024 * 1024
        private val RETRY_DELAYS_S = listOf(2, 3, 5, 10, 15, 20, 30, 30, 30, 30)
    }
}
