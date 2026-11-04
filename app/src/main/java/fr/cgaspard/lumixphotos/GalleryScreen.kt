package fr.cgaspard.lumixphotos

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Fichiers de la section qui commence à l'en-tête [headerIndex]. */
private fun Gallery.itemsOfSection(headerIndex: Int): List<CameraItem> =
    entries.asSequence().drop(headerIndex + 1).takeWhile { it is FileEntry }.map { (it as FileEntry).item }.toList()

private fun Gallery.itemsBetween(a: Int, b: Int): List<CameraItem> =
    (min(a, b)..max(a, b)).mapNotNull { (entries.getOrNull(it) as? FileEntry)?.item }

/**
 * Appui long : coche le fichier dès la fin du délai (et passe en mode sélection) ; en glissant
 * ensuite, sélectionne (ou désélectionne) tous les fichiers entre le premier et celui sous le doigt.
 */
@Composable
private fun Modifier.selectionGestures(
    gallery: Gallery,
    s: UiState,
    vm: CameraSession,
    itemAt: (Offset) -> Int?,
    scrollBy: suspend (Float) -> Unit,
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

            // Dès la fin du délai d'appui long, sans attendre que le doigt se lève : le fichier est
            // coché (ou décoché) et on passe en mode sélection. Glisser ensuite étend la même action.
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val selection = DragSelection(
                anchor = start,
                base = currentState.selected,
                select = startItem.id !in currentState.selected,
            ).also { it.current = start }
            vm.dragSelect(selection.base, listOf(startItem), selection.select)
            drag = selection
            pointer = down.position
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // Consommé dès la phase initiale : la grille ne défile pas et la cellule ne reçoit pas de clic
                event.changes.forEach { it.consume() }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                pointer = change.position
                autoScroll = when {
                    pointer.y < edge -> -(edge - pointer.y) / 3
                    pointer.y > size.height - edge -> (pointer.y - (size.height - edge)) / 3
                    else -> 0f
                }
                updateDrag()
            }
            autoScroll = 0f
            drag = null
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
internal fun PhotoGrid(gallery: Gallery, s: UiState, vm: CameraSession, openViewer: (CameraItem) -> Unit) {
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
                .selectionGestures(gallery, s, vm, state::itemAt, { state.scrollBy(it) }),
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
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
                        selectionMode = s.selectionMode,
                        downloaded = s.isDownloaded(entry.item),
                        compact = s.columns >= 6,
                        vm = vm,
                        modifier = Modifier.aspectRatio(1f).clickable {
                            if (s.selectionMode) vm.toggle(entry.item) else openViewer(entry.item)
                        },
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
internal fun DetailsList(gallery: Gallery, s: UiState, vm: CameraSession, openViewer: (CameraItem) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .selectionGestures(gallery, s, vm, state::itemAt, { state.scrollBy(it) }),
        ) {
            items(
                count = gallery.entries.size,
                key = { gallery.entries[it].key },
                contentType = { gallery.entries[it]::class },
            ) { index ->
                when (val entry = gallery.entries[index]) {
                    is DayHeader -> SectionHeader(entry, gallery.itemsOfSection(index), s, vm)
                    is FileEntry -> DetailRow(entry.item, s, vm, openViewer)
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
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 40.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(header.label, style = MaterialTheme.typography.titleMedium)
            Text(
                "${header.count} fichier(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (s.selectionMode) {
            IconButton(onClick = { vm.toggleDay(items) }) {
                if (allSelected) {
                    Icon(Icons.Default.CheckCircle, "Désélectionner ce jour", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.Circle, "Sélectionner ce jour", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // Même hauteur de ligne que l'icône du mode sélection : rien ne bouge en y entrant
            Spacer(Modifier.size(48.dp))
        }
    }
}

private val DETAIL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

internal fun describe(item: CameraItem, meta: FileMeta?) = listOf(
    meta?.date?.format(DETAIL_DATE) ?: "date…",
    meta?.size?.takeIf { it > 0 }?.let(::formatSize) ?: "taille…",
    if (item.isVideo) "Vidéo" else "Photo",
).joinToString(" · ")

@Composable
private fun DetailRow(item: CameraItem, s: UiState, vm: CameraSession, openViewer: (CameraItem) -> Unit) {
    val selected = item.id in s.selected
    ListItem(
        modifier = Modifier
            .padding(end = 28.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable { if (s.selectionMode) vm.toggle(item) else openViewer(item) },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        leadingContent = {
            Thumbnail(
                item = item,
                selected = false,
                selectionMode = false,
                downloaded = false,
                compact = true,
                vm = vm,
                modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small),
            )
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title)
                item.videoFormat?.let { Badge(it, Modifier.padding(start = 8.dp)) }
                if (s.isDownloaded(item)) {
                    Icon(
                        Icons.Default.CloudDone, "Déjà téléchargé",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp).size(16.dp),
                    )
                }
            }
        },
        supportingContent = { Text(describe(item, s.meta[item.title])) },
        trailingContent = if (s.selectionMode) {
            { Checkbox(checked = selected, onCheckedChange = { vm.toggle(item) }) }
        } else {
            null
        },
    )
}

@Composable
internal fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * Vignette de la grille. Sélectionnée, elle se rétracte et s'arrondit (comme dans Google Photos) ;
 * en mode sélection, un rond vide indique qu'on peut la cocher.
 */
@Composable
private fun Thumbnail(
    item: CameraItem,
    selected: Boolean,
    selectionMode: Boolean,
    downloaded: Boolean,
    compact: Boolean,
    vm: CameraSession,
    modifier: Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(null, item.thumbnailUrl) { value = vm.thumbnail(item) }
    val inset by animateFloatAsState(if (selected) 0.12f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "inset")
    val corner by animateDpAsState(if (selected) 16.dp else 4.dp, label = "corner")
    Box(
        modifier.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1f - inset
                    scaleY = 1f - inset
                }
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
                    Icons.Default.PlayCircle, "Vidéo", tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.Center).size(if (compact) 20.dp else 36.dp),
                )
                if (!compact) item.videoFormat?.let { Badge(it, Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
            }
            if (downloaded) {
                Icon(
                    Icons.Default.CloudDone, "Déjà téléchargé", tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                        .padding(3.dp)
                        .size(if (compact) 10.dp else 14.dp),
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle, "Sélectionné", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(if (compact) 16.dp else 24.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
            )
        } else if (selectionMode) {
            Icon(
                Icons.Outlined.Circle, null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(if (compact) 14.dp else 22.dp),
            )
        }
    }
}

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
