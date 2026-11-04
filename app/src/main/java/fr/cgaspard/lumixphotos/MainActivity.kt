package fr.cgaspard.lumixphotos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = (application as LumixApp).session
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(session) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: CameraSession) {
    val s by vm.state.collectAsStateWithLifecycle()
    val connected = s.connection is ConnectionState.Connected
    val visible = s.visibleItems
    val gallery = remember(visible, s.meta) { Gallery.build(visible, s.meta) }
    // Index (dans la liste affichée) du fichier ouvert en plein écran
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // Au retour dans l'appli : des photos ont pu être effacées de la galerie entre-temps
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshDownloaded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Lumix Photos")
                        if (connected) Subtitle(s)
                    }
                },
                actions = { if (connected) TopActions(s, vm) },
            )
        },
        bottomBar = { if (connected) BottomPanel(s, vm) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val openViewer: (CameraItem) -> Unit = { item -> viewerIndex = visible.indexOf(item).takeIf { it >= 0 } }
            when {
                !connected -> ConnectPanel(s, vm)
                visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (s.items.isEmpty()) "Aucun fichier sur la carte" else "Aucun fichier pour ce filtre")
                }
                s.viewMode == ViewMode.GRID -> PhotoGrid(gallery, s, vm, openViewer)
                else -> DetailsList(gallery, s, vm, openViewer)
            }
        }
    }

    viewerIndex?.let { start ->
        Viewer(visible, start, s, vm, onClose = { viewerIndex = null })
    }
    s.plan?.let { DownloadDialog(it, s.battery, vm) }
    s.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::messageShown,
            confirmButton = { TextButton(onClick = vm::messageShown) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun Subtitle(s: UiState) {
    val parts = buildList {
        add(if (s.filter == DisplayFilter.ALL) "${s.items.size} fichiers" else "${s.visibleItems.size} ${s.filter.label.lowercase()}")
        if (s.metaLoaded < s.items.size) add("dates ${s.metaLoaded}/${s.items.size}")
        s.battery?.let { add("batterie $it") }
    }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (s.battery?.low == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun TopActions(s: UiState, vm: CameraSession) {
    if (s.viewMode == ViewMode.GRID) {
        IconButton(onClick = { vm.setColumns(s.columns + 1) }, enabled = s.columns < CameraSession.MAX_COLUMNS) {
            Icon(Icons.Default.ZoomOut, "Vignettes plus petites")
        }
        IconButton(onClick = { vm.setColumns(s.columns - 1) }, enabled = s.columns > CameraSession.MIN_COLUMNS) {
            Icon(Icons.Default.ZoomIn, "Vignettes plus grandes")
        }
        IconButton(onClick = { vm.setViewMode(ViewMode.DETAILS) }) {
            Icon(Icons.AutoMirrored.Filled.ViewList, "Vue détails")
        }
    } else {
        IconButton(onClick = { vm.setViewMode(ViewMode.GRID) }) { Icon(Icons.Default.GridView, "Vue grille") }
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                "Afficher",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            for (filter in DisplayFilter.entries) {
                DropdownMenuItem(
                    text = { Text(filter.label) },
                    leadingIcon = { RadioButton(selected = s.filter == filter, onClick = null) },
                    onClick = { menu = false; vm.setFilter(filter) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Actualiser") },
                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                onClick = { menu = false; vm.refresh() },
            )
            DropdownMenuItem(
                text = { Text("Déconnecter") },
                leadingIcon = { Icon(Icons.Default.LinkOff, null) },
                onClick = { menu = false; vm.disconnect() },
            )
        }
    }
}

@Composable
private fun ConnectPanel(s: UiState, vm: CameraSession) {
    val working = s.connection as? ConnectionState.Working
    var ssid by remember(s.wifiSsid) { mutableStateOf(s.wifiSsid) }
    var password by remember(s.wifiPassword) { mutableStateOf(s.wifiPassword) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Connexion Wi-Fi automatique", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
        val context = LocalContext.current
        var scanError by remember { mutableStateOf<String?>(null) }
        FilledTonalButton(
            onClick = {
                scanError = null
                WifiQr.scan(
                    context,
                    onResult = { credentials ->
                        if (credentials == null) {
                            scanError = "Ce QR code ne contient pas d'identifiants Wi-Fi."
                        } else {
                            ssid = credentials.ssid
                            password = credentials.password
                            vm.setWifi(credentials.ssid, credentials.password)
                            vm.connect()
                        }
                    },
                    onError = { scanError = it },
                )
            },
            enabled = working == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.QrCodeScanner, null)
            Text("  Scanner le QR code de l'appareil")
        }
        Text(
            "Sur l'appareil : MENU › Wi-Fi › Fonction Wi-Fi › Nouvelle connexion › " +
                "« Prise de vue à distance et affichage » : le QR code s'affiche à l'écran.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = { Text("Wi-Fi de l'appareil (ex. FZ1000-A1B2C3)") },
            singleLine = true,
            enabled = working == null,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe (affiché sur l'appareil)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = working == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Sinon, connectez vous-même le téléphone au Wi-Fi de l'appareil ($DIRECT_MODE_IP).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.hostInput,
            onValueChange = vm::setHostInput,
            label = { Text("Adresse IP (vide = recherche auto)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = working == null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (working != null) {
            CircularProgressIndicator()
            Text(working.message, textAlign = TextAlign.Center)
        } else {
            Button(
                onClick = { vm.setWifi(ssid, password); vm.connect() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Se connecter") }
        }
        (s.connection as? ConnectionState.Failed)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/** Fichiers de la section qui commence à l'en-tête [headerIndex]. */
private fun Gallery.itemsOfSection(headerIndex: Int): List<CameraItem> =
    entries.asSequence().drop(headerIndex + 1).takeWhile { it is FileEntry }.map { (it as FileEntry).item }.toList()

private fun Gallery.itemsBetween(a: Int, b: Int): List<CameraItem> =
    (min(a, b)..max(a, b)).mapNotNull { (entries.getOrNull(it) as? FileEntry)?.item }

/**
 * Appui long : sans bouger, ouvre le plein écran au relâchement ; en glissant, sélectionne
 * (ou désélectionne) tous les fichiers entre le premier et celui sous le doigt.
 */
@Composable
private fun Modifier.selectionGestures(
    gallery: Gallery,
    s: UiState,
    vm: CameraSession,
    itemAt: (Offset) -> Int?,
    scrollBy: suspend (Float) -> Unit,
    openViewer: (CameraItem) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentGallery by rememberUpdatedState(gallery)
    val currentState by rememberUpdatedState(s)
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    // Défilement automatique quand le doigt approche du haut ou du bas pendant une sélection
    var autoScroll by remember { mutableFloatStateOf(0f) }
    var drag by remember { mutableStateOf<DragSelection?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }

    fun updateDrag() {
        val d = drag ?: return
        val index = itemAt(pointer) ?: return
        if (index != d.current) {
            d.current = index
            vm.dragSelect(d.base, currentGallery.itemsBetween(d.anchor, index), d.select)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            if (autoScroll != 0f) {
                scrollBy(autoScroll)
                updateDrag()
            }
        }
    }

    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = itemAt(down.position) ?: return@awaitEachGesture
            val startItem = (currentGallery.entries.getOrNull(start) as? FileEntry)?.item ?: return@awaitEachGesture
            // Attente de l'appui long, sans rien consommer : un toucher bref ou un défilement restent normaux
            val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var stillDown = true
                while (stillDown) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    stillDown = change != null && change.pressed && event.changes.size == 1 &&
                        (change.position - down.position).getDistance() <= viewConfiguration.touchSlop
                }
                true
            }
            if (interrupted != null) return@awaitEachGesture

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            var dragging = false
            pointer = down.position
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // Consommé dès la phase initiale : la grille ne défile pas et la cellule ne reçoit pas de clic
                event.changes.forEach { it.consume() }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                pointer = change.position
                if (!dragging && itemAt(pointer).let { it != null && it != start }) {
                    dragging = true
                    drag = DragSelection(
                        anchor = start,
                        base = currentState.selected,
                        select = startItem.id !in currentState.selected,
                    )
                }
                if (dragging) {
                    autoScroll = when {
                        pointer.y < edge -> -(edge - pointer.y) / 3
                        pointer.y > size.height - edge -> (pointer.y - (size.height - edge)) / 3
                        else -> 0f
                    }
                    updateDrag()
                }
            }
            autoScroll = 0f
            drag = null
            if (!dragging) openViewer(startItem)
        }
    }
}

private class DragSelection(val anchor: Int, val base: Set<String>, val select: Boolean) {
    var current = -1
}

private fun LazyGridState.itemAt(position: Offset): Int? = layoutInfo.visibleItemsInfo.firstOrNull {
    position.x >= it.offset.x && position.x < it.offset.x + it.size.width &&
        position.y >= it.offset.y && position.y < it.offset.y + it.size.height
}?.index

private fun LazyListState.itemAt(position: Offset): Int? = layoutInfo.visibleItemsInfo.firstOrNull {
    position.y >= it.offset && position.y < it.offset + it.size
}?.index

@Composable
private fun PhotoGrid(gallery: Gallery, s: UiState, vm: CameraSession, openViewer: (CameraItem) -> Unit) {
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val columns by rememberUpdatedState(s.columns)
    Box(
        Modifier
            .fillMaxSize()
            .pinchToZoom { zoomIn -> vm.setColumns(if (zoomIn) columns - 1 else columns + 1) }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(s.columns),
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .selectionGestures(gallery, s, vm, state::itemAt, { state.scrollBy(it) }, openViewer),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                count = gallery.entries.size,
                key = { gallery.entries[it].key },
                span = { if (gallery.entries[it] is DayHeader) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { gallery.entries[it]::class },
            ) { index ->
                when (val entry = gallery.entries[index]) {
                    is DayHeader -> SectionHeader(entry, gallery.itemsOfSection(index), s, vm)
                    is FileEntry -> Thumbnail(
                        item = entry.item,
                        selected = entry.item.id in s.selected,
                        downloaded = s.isDownloaded(entry.item),
                        compact = s.columns >= 6,
                        vm = vm,
                        modifier = Modifier.aspectRatio(1f).clickable { vm.toggle(entry.item) },
                    )
                }
            }
        }
        FastScroller(
            firstVisible = state.firstVisibleItemIndex,
            total = gallery.entries.size,
            label = { gallery.headerAt(it)?.monthLabel ?: "" },
            onScrollTo = { scope.launch { state.scrollToItem(it) } },
        )
    }
}

@Composable
private fun DetailsList(gallery: Gallery, s: UiState, vm: CameraSession, openViewer: (CameraItem) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .selectionGestures(gallery, s, vm, state::itemAt, { state.scrollBy(it) }, openViewer),
        ) {
            items(
                count = gallery.entries.size,
                key = { gallery.entries[it].key },
                contentType = { gallery.entries[it]::class },
            ) { index ->
                when (val entry = gallery.entries[index]) {
                    is DayHeader -> SectionHeader(entry, gallery.itemsOfSection(index), s, vm)
                    is FileEntry -> DetailRow(entry.item, s, vm)
                }
            }
        }
        FastScroller(
            firstVisible = state.firstVisibleItemIndex,
            total = gallery.entries.size,
            label = { gallery.headerAt(it)?.monthLabel ?: "" },
            onScrollTo = { scope.launch { state.scrollToItem(it) } },
        )
    }
}

@Composable
private fun SectionHeader(header: DayHeader, items: List<CameraItem>, s: UiState, vm: CameraSession) {
    val allSelected = items.isNotEmpty() && items.all { it.id in s.selected }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 44.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(header.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("${header.count} fichier(s)", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { vm.toggleDay(items) }) {
            if (allSelected) {
                Icon(Icons.Default.CheckCircle, "Désélectionner ce jour", tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Outlined.Circle, "Sélectionner ce jour")
            }
        }
    }
}

private val DETAIL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

private fun describe(item: CameraItem, meta: FileMeta?) = listOf(
    meta?.date?.format(DETAIL_DATE) ?: "date…",
    meta?.size?.takeIf { it > 0 }?.let(::formatSize) ?: "taille…",
    if (item.isVideo) "Vidéo" else "Photo",
).joinToString(" · ")

@Composable
private fun DetailRow(item: CameraItem, s: UiState, vm: CameraSession) {
    val selected = item.id in s.selected
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.toggle(item) }
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .padding(start = 8.dp, end = 40.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(
            item = item,
            selected = false,
            downloaded = false,
            compact = true,
            vm = vm,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                item.videoFormat?.let { Badge(it, Modifier.padding(start = 6.dp)) }
                if (s.isDownloaded(item)) {
                    Icon(
                        Icons.Default.CloudDone, "Déjà téléchargé",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
            }
            Text(
                describe(item, s.meta[item.title]),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = selected, onCheckedChange = { vm.toggle(item) })
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun Thumbnail(
    item: CameraItem,
    selected: Boolean,
    downloaded: Boolean,
    compact: Boolean,
    vm: CameraSession,
    modifier: Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(null, item.thumbnailUrl) { value = vm.thumbnail(item) }
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(if (compact) 3.dp else 4.dp, accent) else Modifier),
    ) {
        bitmap?.let {
            Image(it, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } ?: Text(
            item.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(4.dp),
        )
        if (item.isVideo) {
            Icon(
                Icons.Default.PlayCircle, "Vidéo", tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(if (compact) 20.dp else 36.dp),
            )
            if (!compact) item.videoFormat?.let { Badge(it, Modifier.align(Alignment.BottomEnd).padding(4.dp)) }
        }
        if (downloaded) {
            Icon(
                Icons.Default.CloudDone, "Déjà téléchargé", tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).size(if (compact) 12.dp else 18.dp),
            )
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle, "Sélectionné", tint = accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(if (compact) 14.dp else 24.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

/** Plein écran : aperçu 1620×1080 servi par l'appareil, glisser pour passer au fichier voisin. */
@Composable
private fun Viewer(items: List<CameraItem>, start: Int, s: UiState, vm: CameraSession, onClose: () -> Unit) {
    if (items.isEmpty()) return onClose()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.size - 1)) { items.size }
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].id }) { page ->
                val item = items[page]
                val preview by produceState<ImageBitmap?>(null, item.previewUrl) { value = vm.preview(item) }
                val thumb by produceState<ImageBitmap?>(null, item.thumbnailUrl) { value = vm.thumbnail(item) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // La vignette, déjà en cache, s'affiche le temps de charger l'aperçu
                    preview?.let { ZoomableImage(it, item.title) }
                        ?: thumb?.let { Image(it, contentDescription = item.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    if (preview == null) CircularProgressIndicator(color = Color.White)
                    if (item.isVideo) {
                        Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(72.dp))
                    }
                }
            }
            val item = items[pager.currentPage]
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .safeDrawingPadding()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    item.videoFormat?.let { Badge(it, Modifier.padding(end = 8.dp)) }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fermer", tint = Color.White) }
                }
                Text(describe(item, s.meta[item.title]), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                if (item.isVideo) {
                    Text(
                        "Aperçu fixe : l'appareil est trop lent en Wi-Fi pour lire la vidéo en direct.",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .safeDrawingPadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${pager.currentPage + 1} / ${items.size}", color = Color.White, modifier = Modifier.weight(1f))
                s.localCopy(item)?.let { copy ->
                    val context = LocalContext.current
                    // Ouvre la copie du téléphone : seule façon de lire une vidéo (trop lent en Wi-Fi)
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(copy.uri, item.mimeType)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        }
                    }) {
                        Icon(Icons.Default.CloudDone, null, tint = Color.White)
                        Text("  Ouvrir", color = Color.White)
                    }
                }
                val selected = item.id in s.selected
                FilledTonalButton(onClick = { vm.toggle(item) }) {
                    Icon(if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle, null)
                    Text(if (selected) "  Sélectionné" else "  Sélectionner")
                }
            }
        }
    }
}

/**
 * Image du plein écran : pincer ou double appui pour zoomer, glisser pour se déplacer. Tant
 * qu'on n'a pas zoomé, un glissement à un doigt reste pour le changement de page.
 */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap, description: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        fun clamp(o: Offset, s: Float): Offset {
            val maxX = width * (s - 1) / 2
            val maxY = height * (s - 1) / 2
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        Image(
            bitmap,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                            offset = clamp((Offset(width / 2, height / 2) - tap) * (DOUBLE_TAP_ZOOM - 1), DOUBLE_TAP_ZOOM)
                        }
                    })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2 || scale > 1f) {
                                val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                offset = if (newScale == 1f) Offset.Zero else clamp(offset + event.calculatePan(), newScale)
                                scale = newScale
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

private const val DOUBLE_TAP_ZOOM = 2.5f

/** Pincer pour changer le nombre de colonnes, sans gêner le défilement à un doigt. */
private fun Modifier.pinchToZoom(onStep: (zoomIn: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var scale = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                scale *= event.calculateZoom()
                if (scale > 1.35f) {
                    onStep(true); scale = 1f
                } else if (scale < 0.75f) {
                    onStep(false); scale = 1f
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** Poignée à droite, comme dans Google Photos : on la fait glisser pour parcourir les mois. */
@Composable
private fun FastScroller(
    firstVisible: Int,
    total: Int,
    label: (Int) -> String,
    onScrollTo: (Int) -> Unit,
) {
    if (total < 40) return
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val thumbHeight = 52.dp
        val thumbPx = with(density) { thumbHeight.toPx() }
        val travel = (constraints.maxHeight - thumbPx).coerceAtLeast(1f)
        val fraction = if (dragging) dragFraction else firstVisible.toFloat() / (total - 1)
        val offsetY = (fraction * travel).roundToInt()
        val index = (fraction * (total - 1)).roundToInt()

        fun dragTo(y: Float) {
            dragFraction = ((y - thumbPx / 2) / travel).coerceIn(0f, 1f)
            onScrollTo((dragFraction * (total - 1)).roundToInt())
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(28.dp)
                .fillMaxHeight()
                .pointerInput(total) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; dragTo(it.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ -> change.consume(); dragTo(change.position.y) },
                    )
                }
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, offsetY) }
                .padding(end = 2.dp)
                .size(width = 22.dp, height = thumbHeight)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else 0.75f),
                    RoundedCornerShape(11.dp),
                )
        )
        if (dragging) {
            Text(
                label(index),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-with(density) { 40.dp.roundToPx() }, offsetY) }
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun BottomPanel(s: UiState, vm: CameraSession) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val d = s.download
            if (d != null) {
                DownloadProgressPanel(d, vm)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectionMenu(s, vm)
                    Spacer(Modifier.weight(1f))
                    if (s.selected.isNotEmpty()) {
                        val sizes = s.items.filter { it.id in s.selected }.map { s.meta[it.title]?.size ?: -1L }
                        val known = sizes.filter { it > 0 }.sum()
                        Text(
                            (if (sizes.any { it <= 0 }) "≥ " else "") + formatSize(known),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Button(onClick = vm::prepareDownload, enabled = s.selected.isNotEmpty()) {
                        Icon(Icons.Default.Download, null)
                        Text(" ${s.selected.size}")
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionMenu(s: UiState, vm: CameraSession) {
    var open by remember { mutableStateOf(false) }
    val visible = s.visibleItems
    val photos = visible.count { !it.isVideo }
    val videos = visible.size - photos
    val fresh = visible.count { !s.isDownloaded(it) }
    Box {
        TextButton(onClick = { open = true }) { Text("Sélection ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Tout ($photos + $videos)") }, onClick = { open = false; vm.selectAll() })
            DropdownMenuItem(text = { Text("Photos uniquement ($photos)") }, onClick = { open = false; vm.selectPhotos() })
            DropdownMenuItem(text = { Text("Vidéos uniquement ($videos)") }, onClick = { open = false; vm.selectVideos() })
            DropdownMenuItem(text = { Text("Pas encore téléchargés ($fresh)") }, onClick = { open = false; vm.selectNew() })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Aucun") },
                enabled = s.selected.isNotEmpty(),
                onClick = { open = false; vm.clearSelection() },
            )
            Text(
                "Astuce : appui long puis glisser pour sélectionner une série",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(220.dp),
            )
        }
    }
}

@Composable
private fun DownloadProgressPanel(d: DownloadProgress, vm: CameraSession) {
    val fileSize = if (d.totalBytes > 0) "${formatSize(d.bytes)} / ${formatSize(d.totalBytes)}" else formatSize(d.bytes)
    Text("${d.index}/${d.count} · ${d.fileName} · $fileSize", style = MaterialTheme.typography.bodySmall, maxLines = 1)
    Spacer(Modifier.height(4.dp))
    if (d.totalBytes > 0) {
        LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    if (d.count > 1 && d.plannedBytes > 0) {
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { d.totalFraction },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    d.retrying?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val done = d.doneBytes + d.bytes
        val total = buildString {
            append("Total ${formatSize(done)}")
            if (d.plannedBytes > 0) append(" / ${formatSize(d.plannedBytes)}")
            if (d.bytesPerSecond > 0) {
                append(" · ${formatSize(d.bytesPerSecond)}/s")
                if (d.plannedBytes > done) append(" · reste ~${formatDuration((d.plannedBytes - done) / d.bytesPerSecond)}")
            }
        }
        Text(total, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::cancelDownload) { Text("Annuler") }
    }
}

@Composable
private fun DownloadDialog(plan: DownloadPlan, battery: Battery?, vm: CameraSession) {
    var reduced by remember { mutableStateOf(false) }
    // Par défaut, on ne recopie pas ce qui est déjà sur le téléphone (cela créerait des doublons)
    var skipExisting by remember { mutableStateOf(true) }
    val items = plan.items(skipExisting)
    val photos = items.count { !it.isVideo }
    val bytes = plan.bytes(skipExisting, reduced)
    val seconds = bytes / plan.bytesPerSecond.coerceAtLeast(1)
    val enoughSpace = bytes + DownloadPlan.FREE_SPACE_MARGIN <= plan.freeBytes
    val context = LocalContext.current
    // Pour suivre la progression dans les notifications quand l'appli est en arrière-plan
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    AlertDialog(
        onDismissRequest = vm::dismissPlan,
        title = { Text("Télécharger ${items.size} fichier(s)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$photos photo(s), ${items.size - photos} vidéo(s)")
                if (plan.alreadyDownloaded.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { skipExisting = !skipExisting },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = skipExisting, onCheckedChange = { skipExisting = it })
                        Text("Ignorer les ${plan.alreadyDownloaded.size} fichier(s) déjà sur le téléphone")
                    }
                }
                if (photos > 0) {
                    Text("Photos :", fontWeight = FontWeight.SemiBold)
                    QualityOption("Taille originale", selected = !reduced) { reduced = false }
                    QualityOption("Taille réduite (1620×1080, ~1 Mo, environ 8× plus rapide)", selected = reduced) { reduced = true }
                }
                val unknown = plan.unknownSizes(skipExisting)
                Text("≈ ${formatSize(bytes)}" + if (unknown > 0 && !reduced) " (taille encore inconnue pour $unknown fichier(s))" else "")
                Text(
                    "Durée estimée : ~${formatDuration(seconds)} à ${formatSize(plan.bytesPerSecond)}/s. " +
                        "Le transfert continue si vous quittez l'appli, et reprend tout seul après une coupure Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!enoughSpace) {
                    Text(
                        "Espace insuffisant sur le téléphone : ${formatSize(plan.freeBytes)} libres. " +
                            "Sélectionnez moins de fichiers ou choisissez la taille réduite.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (battery?.low == true && seconds > 300) {
                    Text(
                        "Batterie de l'appareil faible ($battery) : le transfert risque d'être interrompu.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = items.isNotEmpty() && enoughSpace,
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.startDownload(reduced, skipExisting)
                },
            ) { Text("Télécharger") }
        },
        dismissButton = { TextButton(onClick = vm::dismissPlan) { Text("Annuler") } },
    )
}

@Composable
private fun QualityOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, Modifier.padding(start = 8.dp))
    }
}
