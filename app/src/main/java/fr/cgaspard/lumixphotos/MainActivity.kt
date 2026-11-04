package fr.cgaspard.lumixphotos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = (application as LumixApp).session
        setContent {
            LumixTheme {
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
    // Index (dans la liste affichée) du fichier ouvert en plein écran ; conservé quand on tourne l'écran
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // Au retour dans l'appli : des photos ont pu être effacées de la galerie entre-temps
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshDownloaded() }
    BackHandler(enabled = s.selectionMode && viewerIndex == null) { vm.exitSelection() }

    // Prise de vue à distance : l'écran de télécommande remplace la galerie
    val remote by vm.remote.state.collectAsStateWithLifecycle()
    if (connected && (remote.active || remote.starting)) {
        RemoteScreen(vm, s)
        return
    }
    // Juste après la connexion (ou si la prise de vue n'a pas pu démarrer) : que veut-on faire ?
    if (connected && s.destination != Destination.GALLERY) {
        ChooserScreen(s, vm, message = remote.message)
        return
    }

    val selecting = connected && s.selectionMode
    // En mode sélection, la barre du haut prend une teinte d'accent, comme dans Google Photos
    val barColor by animateColorAsState(
        if (selecting) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        label = "barColor",
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (connected) {
                TopAppBar(
                    navigationIcon = {
                        if (selecting) IconButton(onClick = vm::exitSelection) { Icon(Icons.Default.Close, "Quitter la sélection") }
                    },
                    title = {
                        if (selecting) {
                            val n = s.selected.size
                            Text(if (n > 1) "$n sélectionnés" else "$n sélectionné", maxLines = 1)
                        } else {
                            Column {
                                Text("Lumix Photos", maxLines = 1)
                                Subtitle(s)
                            }
                        }
                    },
                    actions = { TopActions(s, vm) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = barColor,
                        scrolledContainerColor = if (selecting) barColor else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = { if (connected) BottomPanel(s, vm) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val openViewer: (CameraItem) -> Unit = { item -> viewerIndex = visible.indexOf(item).takeIf { it >= 0 } }
            if (!connected) {
                ConnectScreen(s, vm)
                return@Column
            }
            FilterChips(s, vm)
            Box(Modifier.weight(1f)) {
                when {
                    s.loadingCard != null && s.items.isEmpty() -> LoadingCard(s.loadingCard!!)
                    visible.isEmpty() -> EmptyState(if (s.items.isEmpty()) "Aucun fichier sur la carte" else "Aucun fichier pour ce filtre")
                    s.viewMode == ViewMode.GRID -> PhotoGrid(gallery, s, vm, openViewer)
                    else -> DetailsList(gallery, s, vm, openViewer)
                }
            }
        }
    }

    viewerIndex?.let { start ->
        Viewer(visible, start, s, vm, onClose = { viewerIndex = null })
    }
    s.plan?.let { DownloadDialog(it, s.battery, vm) }
    // Échec du passage en prise de vue : l'écran de télécommande ne s'est pas ouvert pour l'afficher
    remote.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm.remote::messageShown,
            confirmButton = { TextButton(onClick = vm.remote::messageShown) { Text("OK") } },
            text = { Text(msg) },
        )
    }
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
        add(s.loadingCard ?: "${s.items.size} fichiers")
        if (s.metaLoaded < s.items.size) add("dates ${s.metaLoaded}/${s.items.size}")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        s.battery?.let { BatteryIcon(it, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(start = 8.dp)) }
    }
}

/** Filtres d'affichage, toujours visibles sous la barre du haut. */
@Composable
private fun FilterChips(s: UiState, vm: CameraSession) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (filter in DisplayFilter.entries) {
            val selected = s.filter == filter
            FilterChip(
                selected = selected,
                onClick = { vm.setFilter(filter) },
                label = { Text(filter.label) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun LoadingCard(text: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp).padding(bottom = 12.dp),
        )
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TopActions(s: UiState, vm: CameraSession) {
    if (!s.selectionMode) {
        // Pendant un téléchargement, l'appareil doit rester en lecture
        IconButton(onClick = vm::enterRemote, enabled = s.download == null) {
            Icon(Icons.Default.PhotoCamera, "Prise de vue à distance")
        }
    }
    // En mode sélection, on laisse la place au nombre de fichiers sélectionnés
    if (s.viewMode == ViewMode.GRID && !s.selectionMode) {
        IconButton(onClick = { vm.setColumns(s.columns + 1) }, enabled = s.columns < CameraSession.MAX_COLUMNS) {
            Icon(Icons.Default.ZoomOut, "Vignettes plus petites")
        }
        IconButton(onClick = { vm.setColumns(s.columns - 1) }, enabled = s.columns > CameraSession.MIN_COLUMNS) {
            Icon(Icons.Default.ZoomIn, "Vignettes plus grandes")
        }
    }
    if (s.viewMode == ViewMode.GRID) {
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
