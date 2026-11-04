package fr.cgaspard.lumixphotos

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Plein écran : aperçu 1620×1080 servi par l'appareil, glisser pour passer au fichier voisin. */
@Composable
internal fun Viewer(items: List<CameraItem>, start: Int, s: UiState, vm: CameraSession, onClose: () -> Unit) {
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
