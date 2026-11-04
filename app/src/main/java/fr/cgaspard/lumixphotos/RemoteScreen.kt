package fr.cgaspard.lumixphotos

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.os.Build
import android.graphics.RuntimeShader
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Réglages proposés en accès direct sous la visée, dans cet ordre quand l'appareil les propose. */
private val QUICK_SETTINGS = listOf("aperture", "shutter", "menu_item_id_exposure2", "menu_item_id_sensitivity", "menu_item_id_whitebalance", "menu_item_id_afmode")

private sealed interface Sheet {
    data object All : Sheet
    data class One(val settingId: String) : Sheet
}

/** Télécommande : visée en direct, déclencheur, vidéo, zoom et réglages, en portrait comme en paysage. */
@Composable
internal fun RemoteScreen(vm: CameraSession, s: UiState) {
    val remote = vm.remote
    val r by remote.state.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val snackbar = remember { SnackbarHostState() }
    // Bref flash blanc à chaque photo, comme un obturateur
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    BackHandler { vm.exitRemote() }
    // Pas de visée (ni de trafic Wi-Fi inutile) quand l'appli n'est plus à l'écran
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { remote.pauseStream() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { remote.resumeStream() }
    LaunchedEffect(r.message) {
        r.message?.let {
            snackbar.showSnackbar(it)
            remote.messageShown()
        }
    }
    val shoot = {
        remote.capture()
        scope.launch {
            flash.snapTo(0.8f)
            flash.animateTo(0f, tween(300))
        }
        Unit
    }

    LumixTheme(dark = true) {
        Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val landscape = maxWidth > maxHeight
                if (landscape) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            StatusBar(r, s, onBack = vm::exitRemote)
                            LiveView(remote, r, flash.value, Modifier.weight(1f).fillMaxWidth())
                            QuickSettings(r) { sheet = it }
                        }
                        Controls(r, remote, shoot, vertical = true, modifier = Modifier.width(132.dp).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        StatusBar(r, s, onBack = vm::exitRemote)
                        LiveView(remote, r, flash.value, Modifier.weight(1f).fillMaxWidth())
                        QuickSettings(r) { sheet = it }
                        Controls(r, remote, shoot, vertical = false, modifier = Modifier.fillMaxWidth().height(132.dp))
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            sheet?.let { current -> SettingsSheet(r, remote, current, onChange = { sheet = it }, onDismiss = { sheet = null }) }
        }
    }
}

@Composable
private fun StatusBar(r: RemoteState, s: UiState, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour à la galerie") }
        if (r.recording) {
            Box(Modifier.size(10.dp).background(Color.Red, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(formatClock(r.recordSeconds), fontWeight = FontWeight.SemiBold, color = Color.Red)
        } else {
            Text("Prise de vue", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.weight(1f))
        val parts = buildList {
            r.photosLeft?.let { add("$it photos") }
            r.videoSecondsLeft?.let { add("${formatClock(it)} de vidéo") }
            s.battery?.let { add("batterie $it") }
        }
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (s.battery?.low == true) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.75f),
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}

/** Visée : toucher l'image fait la mise au point à cet endroit. */
@Composable
private fun LiveView(remote: RemoteController, r: RemoteState, flash: Float, modifier: Modifier) {
    val frame by remote.frame.collectAsStateWithLifecycle()
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val focusAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val image: ImageBitmap? = frame
        if (image == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(if (r.starting) "Passage en prise de vue…" else "Visée en attente…", color = Color.White.copy(alpha = 0.7f))
            }
            return@BoxWithConstraints
        }
        // Zone réellement occupée par l'image (proportions conservées)
        val ratio = image.width.toFloat() / image.height
        val boxRatio = constraints.maxWidth.toFloat() / constraints.maxHeight
        val w = if (boxRatio > ratio) constraints.maxHeight * ratio else constraints.maxWidth.toFloat()
        val h = w / ratio
        val density = LocalDensity.current
        Box(
            Modifier
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .then(if (r.recording) Modifier.border(3.dp, Color.Red) else Modifier)
                .pointerInputTap { offset ->
                    focusPoint = offset
                    remote.focusAt(offset.x / w, offset.y / h)
                    scope.launch {
                        focusAlpha.snapTo(1f)
                        focusAlpha.animateTo(0f, tween(1_200))
                    }
                },
        ) {
            // Image de 640 px agrandie jusqu'à 4× sur tablette : filtrage soigné, puis renforcement de netteté
            Image(
                image,
                contentDescription = "Visée",
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize().sharpened(scale = w / image.width),
            )
            focusPoint?.let { p ->
                val half = with(density) { 36.dp.toPx() }
                Box(
                    Modifier
                        .offset { IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt()) }
                        .size(72.dp)
                        .alpha(focusAlpha.value)
                        .border(2.dp, Color(0xFFFFD54F), RoundedCornerShape(6.dp)),
                )
            }
            if (flash > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash)))
        }
    }
}

/**
 * Masque flou (unsharp mask) calculé par la carte graphique (Android 13+) : compare chaque point
 * à ses voisins situés à un pixel *source* de distance, pour accentuer les détails de l'image
 * d'origine sans amplifier l'escalier dû à l'agrandissement.
 */
@Composable
private fun Modifier.sharpened(scale: Float): Modifier {
    if (Build.VERSION.SDK_INT < 33 || scale < 1.2f) return this
    // Compilé une seule fois ; seul l'écart entre points suit la taille d'affichage
    val shader = remember { RuntimeShader(SHARPEN_SHADER) }
    val effect = remember(scale) {
        shader.setFloatUniform("texel", scale)
        shader.setFloatUniform("amount", 0.4f)
        android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
    // Sans découpage, l'effet étire les pixels du bord de l'image sur tout le reste de l'écran
    return graphicsLayer {
        clip = true
        renderEffect = effect
    }
}

private const val SHARPEN_SHADER = """
    uniform shader content;
    uniform float texel;
    uniform float amount;
    half4 main(float2 p) {
        half4 c = content.eval(p);
        half4 blur = (content.eval(p + float2(texel, 0)) + content.eval(p - float2(texel, 0)) +
                      content.eval(p + float2(0, texel)) + content.eval(p - float2(0, texel))) * 0.25;
        return half4(clamp(c.rgb + (c.rgb - blur.rgb) * amount, 0.0, 1.0), c.a);
    }
"""

private fun Modifier.pointerInputTap(onTap: (Offset) -> Unit) = this.then(
    Modifier.pointerInput(Unit) { detectTapGestures(onTap = onTap) }
)

@Composable
private fun QuickSettings(r: RemoteState, open: (Sheet) -> Unit) {
    val byId = r.settings.associateBy { it.id }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (id in QUICK_SETTINGS) {
            val setting = byId[id] ?: continue
            AssistChip(
                onClick = { open(Sheet.One(id)) },
                label = {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(setting.title, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                        Text(r.currentLabel(setting), style = MaterialTheme.typography.labelLarge)
                    }
                },
                colors = AssistChipDefaults.assistChipColors(labelColor = Color.White),
            )
        }
        AssistChip(
            onClick = { open(Sheet.All) },
            label = { Text("Tous les réglages") },
            leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) },
            enabled = r.settings.isNotEmpty(),
            colors = AssistChipDefaults.assistChipColors(labelColor = Color.White, leadingIconContentColor = Color.White),
        )
    }
}

/** Zoom (maintenir), déclencheur et vidéo ; en colonne à droite en paysage, en ligne en bas en portrait. */
@Composable
private fun Controls(r: RemoteState, remote: RemoteController, shoot: () -> Unit, vertical: Boolean, modifier: Modifier) {
    val zoom = @Composable { ZoomButtons(remote, vertical) }
    val shutter = @Composable { ShutterButton(enabled = r.active && !r.shooting && !r.recording, onClick = shoot) }
    val video = @Composable { VideoButton(recording = r.recording, enabled = r.active, onClick = remote::toggleVideo) }
    if (vertical) {
        Column(modifier, verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            video(); shutter(); zoom()
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            zoom(); shutter(); video()
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .padding(8.dp)
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .clickable(enabled = enabled, onClickLabel = "Prendre une photo", onClick = onClick),
    )
}

@Composable
private fun VideoButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .border(3.dp, Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .clickable(enabled = enabled, onClickLabel = if (recording) "Arrêter la vidéo" else "Filmer", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Rond rouge pour démarrer, carré rouge pour arrêter
        Box(Modifier.size(if (recording) 20.dp else 30.dp).background(Color.Red, if (recording) RoundedCornerShape(4.dp) else CircleShape))
    }
}

@Composable
private fun ZoomButtons(remote: RemoteController, vertical: Boolean) {
    val tele = @Composable { ZoomButton(remote, tele = true) }
    val wide = @Composable { ZoomButton(remote, tele = false) }
    val shape = RoundedCornerShape(28.dp)
    if (vertical) {
        Column(Modifier.background(Color.White.copy(alpha = 0.12f), shape), horizontalAlignment = Alignment.CenterHorizontally) { tele(); wide() }
    } else {
        Row(Modifier.background(Color.White.copy(alpha = 0.12f), shape)) { wide(); tele() }
    }
}

/** Zoom tant que le bouton est maintenu. */
@Composable
private fun ZoomButton(remote: RemoteController, tele: Boolean) {
    Box(
        Modifier
            .size(52.dp)
            .pointerInput(tele) {
                detectTapGestures(onPress = {
                    remote.zoom(tele, fast = false)
                    tryAwaitRelease()
                    remote.zoomStop()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (tele) Icons.Default.Add else Icons.Default.Remove, if (tele) "Zoom avant" else "Zoom arrière")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(r: RemoteState, remote: RemoteController, sheet: Sheet, onChange: (Sheet) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = sheet is Sheet.All)) {
        when (sheet) {
            Sheet.All -> LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                item(key = "hint") {
                    Text(
                        "Les réglages proposés dépendent de la molette de l'appareil : en mode auto intelligent (iA), " +
                            "la plupart sont verrouillés ; passez sur P, A, S ou M pour les débloquer. Rafale, retardateur " +
                            "et bracketing se choisissent sur l'appareil. « — » : valeur que l'appareil ne communique pas " +
                            "(elle s'affiche dès qu'on la change ici).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                r.settings.groupBy { it.section }.forEach { (section, settings) ->
                    item(key = "section-$section") {
                        Text(
                            section,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    for (setting in settings) {
                        item(key = setting.id) {
                            val usable = setting.options.any { r.isEnabled(it) }
                            ListItem(
                                headlineContent = { Text(setting.title) },
                                supportingContent = { Text(if (usable) r.currentLabel(setting) else "Indisponible dans ce mode") },
                                modifier = Modifier
                                    .alpha(if (usable) 1f else 0.5f)
                                    .clickable(enabled = usable) { onChange(Sheet.One(setting.id)) },
                            )
                        }
                    }
                }
            }
            is Sheet.One -> {
                val setting = r.settings.firstOrNull { it.id == sheet.settingId } ?: return@ModalBottomSheet
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onChange(Sheet.All) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tous les réglages") }
                        Text(setting.title, style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (option in setting.options) {
                            FilterChip(
                                selected = r.isSelected(setting, option),
                                onClick = { remote.set(setting, option) },
                                enabled = r.isEnabled(option),
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatClock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
