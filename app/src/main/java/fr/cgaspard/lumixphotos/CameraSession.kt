package fr.cgaspard.lumixphotos

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.os.Build
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
import java.io.IOException
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
    NOT_DOWNLOADED("Pas encore téléchargés"),
}

/** Ce que l'utilisateur s'apprête à télécharger, pour la fenêtre de confirmation. */
data class DownloadPlan(
    val items: List<CameraItem>,
    val photoCount: Int,
    val videoCount: Int,
    val originalBytes: Long,
    val unknownSizes: Int,
    val reducedBytes: Long,
    val bytesPerSecond: Long,
)

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
    /** Noms des fichiers présents dans la galerie du téléphone. */
    val downloaded: Set<String> = emptySet(),
    val viewMode: ViewMode = ViewMode.GRID,
    val columns: Int = 3,
    val filter: DisplayFilter = DisplayFilter.ALL,
    val plan: DownloadPlan? = null,
    val download: DownloadProgress? = null,
    val message: String? = null,
) {
    val metaLoaded get() = items.count { it.title in meta }

    val visibleItems: List<CameraItem>
        get() = when (filter) {
            DisplayFilter.ALL -> items
            DisplayFilter.PHOTOS -> items.filter { !it.isVideo }
            DisplayFilter.VIDEOS -> items.filter { it.isVideo }
            DisplayFilter.NOT_DOWNLOADED -> items.filter { it.title !in downloaded }
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
    // Certains fichiers font bloquer le serveur de l'appareil : on ne les redemande jamais
    private val failedImages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        // Remplacé par la lecture de la galerie (voir refreshDownloaded)
        prefs.edit().remove("downloaded").apply()
        scope.launch(Dispatchers.IO) {
            val cached = metaCache.load()
            _state.update { it.copy(meta = cached + it.meta) }
        }
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
                    c.friendlyName()?.let { name ->
                        if (name != _state.value.wifiSsid) setWifi(name, _state.value.wifiPassword)
                    }
                    working("Lecture de la carte SD…")
                    val items = c.listItems()
                    _state.update {
                        it.copy(connection = ConnectionState.Connected(host), items = items, selected = emptySet())
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
        client = null
        rejoinJob?.cancel()
        releaseCameraWifi()
        _state.update {
            it.copy(connection = ConnectionState.Disconnected, items = emptyList(), selected = emptySet(), download = null, battery = null)
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
                // Pendant un téléchargement, le trafic suffit à garder la session ouverte
                if (downloadJob?.isActive == true) continue
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
            val names = MediaSaver.downloadedNames(app)
            _state.update { it.copy(downloaded = names) }
        }
    }

    suspend fun thumbnail(item: CameraItem): ImageBitmap? = loadImage(item.thumbnailUrl, thumbnails, timeoutMs = 5_000)

    /** Aperçu 1620×1080 pour l'affichage plein écran (~1 Mo, moins d'une seconde). */
    suspend fun preview(item: CameraItem): ImageBitmap? = loadImage(item.previewUrl, previews, timeoutMs = 15_000)

    private suspend fun loadImage(url: String?, cache: LruCache<String, ImageBitmap>, timeoutMs: Int): ImageBitmap? {
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
                    bitmap.rotated(response.meta.rotation).asImageBitmap()
                }.onSuccess { cache.put(url, it) }
                    .onFailure { failedImages += url }
                    .getOrNull()
            }
        }
    }

    fun toggle(item: CameraItem) = _state.update {
        it.copy(selected = if (item.id in it.selected) it.selected - item.id else it.selected + item.id)
    }

    /** Sélectionne / désélectionne tous les fichiers d'un jour. */
    fun toggleDay(items: List<CameraItem>) = _state.update { s ->
        val ids = items.map { it.id }
        s.copy(selected = if (s.selected.containsAll(ids)) s.selected - ids.toSet() else s.selected + ids)
    }

    /** Sélection par glissement : [base] est la sélection au début du geste. */
    fun dragSelect(base: Set<String>, range: List<CameraItem>, select: Boolean) = _state.update { s ->
        val ids = range.map { it.id }
        s.copy(selected = if (select) base + ids else base - ids.toSet())
    }

    // Les sélections rapides portent sur ce qui est affiché (filtre compris)
    fun selectAll() = select { true }
    fun selectPhotos() = select { !it.isVideo }
    fun selectVideos() = select { it.isVideo }
    fun selectNew() = select { it.title !in _state.value.downloaded }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    private fun select(predicate: (CameraItem) -> Boolean) = _state.update { s ->
        s.copy(selected = s.visibleItems.filter(predicate).mapTo(mutableSetOf()) { it.id })
    }

    fun isDownloaded(item: CameraItem, s: UiState) = item.title in s.downloaded

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
        val sizes = items.map { s.meta[it.title]?.size ?: -1L }
        val photos = items.count { !it.isVideo }
        _state.update {
            it.copy(
                plan = DownloadPlan(
                    items = items,
                    photoCount = photos,
                    videoCount = items.size - photos,
                    originalBytes = sizes.filter { it > 0 }.sum(),
                    unknownSizes = sizes.count { it <= 0 },
                    // Les aperçus réduits pèsent ~1,1 Mo ; les vidéos restent à leur taille d'origine
                    reducedBytes = items.zip(sizes).sumOf { (item, size) ->
                        if (item.canReduce) REDUCED_PHOTO_BYTES else size.coerceAtLeast(0)
                    },
                    bytesPerSecond = prefs.getLong(KEY_SPEED, DEFAULT_SPEED),
                )
            )
        }
    }

    fun dismissPlan() = _state.update { it.copy(plan = null) }

    fun startDownload(reducedPhotos: Boolean) {
        val c = client ?: return
        val plan = _state.value.plan ?: return
        if (downloadJob?.isActive == true) return
        val planned = if (reducedPhotos) plan.reducedBytes else plan.originalBytes
        // Renseigné tout de suite : le service s'arrête dès qu'il ne voit plus de téléchargement
        _state.update {
            it.copy(plan = null, download = DownloadProgress(1, plan.items.size, plan.items.first().title, plannedBytes = planned))
        }
        downloadJob = scope.launch(Dispatchers.IO) {
            var ok = 0
            var doneBytes = 0L
            val started = SystemClock.elapsedRealtime()
            val errors = mutableListOf<String>()
            plan.items.forEachIndexed { index, item ->
                val reduced = reducedPhotos && item.canReduce
                val url = if (reduced) item.previewUrl!! else item.originalUrl
                val name = if (reduced) item.title.replace(".", "${MediaSaver.REDUCED_SUFFIX}.") else item.title
                _state.update {
                    it.copy(download = DownloadProgress(index + 1, plan.items.size, name, doneBytes = doneBytes, plannedBytes = planned))
                }
                try {
                    cameraHttp.withPermit {
                        val response = withRetries(name) { c.openStream(url, timeoutMs = 30_000) }
                        val length = response.meta.size
                        _state.update { s -> s.copy(download = s.download?.copy(totalBytes = length)) }
                        MediaSaver.save(
                            context = app,
                            item = item,
                            fileName = name,
                            takenAt = response.meta.date ?: _state.value.meta[item.title]?.date,
                            rotation = response.meta.rotation,
                            totalBytes = length,
                            firstStream = response.stream,
                            reopen = { offset -> withRetries(name) { c.openStream(url, timeoutMs = 30_000, offset = offset) }.stream },
                        ) { bytes ->
                            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                            _state.update { s ->
                                s.copy(
                                    download = s.download?.copy(
                                        bytes = bytes,
                                        bytesPerSecond = (doneBytes + bytes) * 1000 / elapsed,
                                        retrying = null,
                                    )
                                )
                            }
                        }
                        doneBytes += length.coerceAtLeast(0)
                    }
                    ok++
                    _state.update { s -> s.copy(downloaded = s.downloaded + item.title, selected = s.selected - item.id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${item.title} : ${e.message}"
                }
            }
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000
            // Débit mesuré, pour estimer la durée des prochains téléchargements
            if (doneBytes > 20_000_000 && seconds > 0) prefs.edit().putLong(KEY_SPEED, doneBytes / seconds).apply()
            val msg = buildString {
                append("$ok fichier(s) enregistré(s) dans DCIM/Lumix")
                append("\n${formatSize(doneBytes)} en ${formatDuration(seconds)}")
                if (errors.isNotEmpty()) append("\n${errors.size} échec(s) : ${errors.first()}")
            }
            _state.update { it.copy(download = null, message = msg) }
            refreshDownloaded()
        }
        // Garde le processus en vie (et le Wi-Fi actif) si l'appli passe en arrière-plan
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
    }

    /**
     * Réessaie [block] pendant ~3 min en cas d'erreur réseau (coupure Wi-Fi, appareil
     * momentanément injoignable), en se reliant à chaque fois au Wi-Fi courant.
     */
    private suspend fun <T> withRetries(fileName: String, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
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

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.update { it.copy(download = null, message = "Téléchargement annulé") }
        refreshDownloaded()
    }

    fun messageShown() = _state.update { it.copy(message = null) }

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
        private const val REDUCED_PHOTO_BYTES = 1_100_000L
        private val RETRY_DELAYS_S = listOf(2, 3, 5, 10, 15, 20, 30, 30, 30, 30)
    }
}
