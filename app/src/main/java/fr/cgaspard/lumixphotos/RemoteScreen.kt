package fr.cgaspard.lumixphotos

import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.outlined.PhotoLibrary
import android.app.Activity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.ln
import androidx.compose.animation.core.animateFloatAsState

/** Réglages proposés en accès direct sous la visée, dans cet ordre quand l'appareil les propose. */
private const val EXPOSURE = "menu_item_id_exposure2"
private const val ISO = "menu_item_id_sensitivity"
private const val WB = "menu_item_id_whitebalance"
private const val AF = "menu_item_id_afmode"
private const val METERING = "menu_item_id_lightmet"
private const val FLASH = "menu_item_id_flash"

/**
 * Raccourcis sous la visée, adaptés à la position de la molette : ce qu'on règle le plus souvent
 * dans ce mode. Seuls ceux que l'appareil permet de changer sont affichés.
 */
private fun quickSettings(mode: ShootingMode?): List<String> = when (mode) {
    ShootingMode.INTELLIGENT_AUTO -> listOf(EXPOSURE, "menu_item_id_ph_sty", "menu_item_id_asprat", "menu_item_id_quality", "menu_item_id_v_quality")
    ShootingMode.PROGRAM -> listOf(EXPOSURE, ISO, WB, AF, METERING, FLASH)
    ShootingMode.APERTURE -> listOf("aperture", EXPOSURE, ISO, WB, AF, METERING)
    ShootingMode.SHUTTER -> listOf("shutter", EXPOSURE, ISO, WB, AF, METERING)
    ShootingMode.MANUAL -> listOf("aperture", "shutter", ISO, WB, AF, METERING)
    ShootingMode.SCENE -> listOf("menu_item_id_scn_gid", EXPOSURE, WB, AF, FLASH)
    ShootingMode.CREATIVE -> listOf("menu_item_id_crtv_ctrl", EXPOSURE, METERING, "menu_item_id_asprat")
    ShootingMode.VIDEO -> listOf("menu_item_id_v_quality", EXPOSURE, ISO, WB, AF, "menu_item_id_4kphoto")
    null -> listOf("aperture", "shutter", EXPOSURE, ISO, WB, AF)
}

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
    // Plein écran : la visée récupère la place des barres système (un glissement depuis le bord les montre)
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        // Pas de mise en veille pendant qu'on cadre
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
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
                            ExposureReadout(r)
                            QuickSettings(r) { sheet = it }
                        }
                        Controls(r, remote, shoot, vertical = true, modifier = Modifier.width(132.dp).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        StatusBar(r, s, onBack = vm::exitRemote)
                        LiveView(remote, r, flash.value, Modifier.weight(1f).fillMaxWidth())
                        ExposureReadout(r)
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
        IconButton(onClick = onBack) { Icon(Icons.Outlined.PhotoLibrary, "Photos et vidéos de la carte") }
        if (r.recording) {
            Box(Modifier.size(10.dp).background(Color.Red, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(formatClock(r.recordSeconds), fontWeight = FontWeight.SemiBold, color = Color.Red)
        } else {
            Text("Prise de vue", style = MaterialTheme.typography.titleMedium)
        }
        r.mode?.let { ModeBadge(it) }
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

/** Position de la molette, ex. « M  Manuel ». */
@Composable
private fun ModeBadge(mode: ShootingMode) {
    Row(
        Modifier
            .padding(start = 12.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(mode.short, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(6.dp))
        Text(mode.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * Jauge de zoom : position entre grand-angle et télé (échelle logarithmique, comme le ressenti du
 * zoom), focale en mm équivalent 24×36 et facteur de grossissement.
 */
@Composable
private fun ZoomGauge(focal: Int, target: Int?, range: IntRange, modifier: Modifier) {
    fun position(f: Int) = (ln(f.toFloat() / range.first) / ln(range.last.toFloat() / range.first)).coerceIn(0f, 1f)
    val animated by animateFloatAsState(position(focal), label = "zoom")
    val factor = focal.toFloat() / range.first
    Column(
        modifier
            .width(260.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "$focal mm  ·  ×" + if (factor < 9.95f) "%.1f".format(factor).replace('.', ',') else factor.roundToInt().toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${range.first}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(6.dp)
                    .background(Color.White.copy(alpha = 0.25f), CircleShape),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animated.coerceAtLeast(0.02f))
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                // Repère de la focale visée pendant un pincement
                target?.let { t ->
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .offset(x = maxWidth * position(t) - 2.dp, y = (-4).dp)
                                .size(width = 4.dp, height = 14.dp)
                                .background(Color.White, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
            Text("${range.last}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** Rappel d'exposition, comme sur l'écran de l'appareil : aussi les valeurs qu'il choisit lui-même. */
@Composable
private fun ExposureReadout(r: RemoteState) {
    val byId = r.settings.associateBy { it.id }
    val parts = buildList {
        byId["aperture"]?.let { add(r.currentLabel(it)) }
        byId["shutter"]?.let { add(r.currentLabel(it)) }
        byId[ISO]?.let { add("ISO " + r.currentLabel(it)) }
        // Correction d'exposition affichée seulement quand elle est active
        byId[EXPOSURE]?.takeIf { r.isEditable(it) }?.let { e -> r.currentLabel(e).takeIf { it != "0" && it != "—" }?.let { add("$it IL") } }
    }.filter { "—" !in it }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("   "),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        textAlign = TextAlign.Center,
    )
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
                .pinchToZoom(remote)
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
            val focal by remote.focal.collectAsStateWithLifecycle()
            val target by remote.zoomTarget.collectAsStateWithLifecycle()
            val range = r.zoomRange
            if (focal != null && range != null) {
                ZoomGauge(focal!!, target, range, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
            // Pendant une vidéo pilotée en Wi-Fi, le FZ1000 bloque ses propres commandes
            if (r.recording) {
                Text(
                    "Commandes de l'appareil bloquées pendant la vidéo : zoomez (pincer, + −) et touchez l'image pour la mise au point",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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

/**
 * Pincer la visée règle une focale cible (écarter les doigts de ×1,5 = focale ×1,5), que
 * l'objectif rejoint au fil du geste. Un seul doigt reste pour la mise au point.
 */
private fun Modifier.pinchToZoom(remote: RemoteController): Modifier = pointerInput(remote) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var start: Int? = null
        var scale = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                if (start == null) {
                    start = remote.focal.value ?: break
                    remote.pinchStarted()
                }
                scale *= event.calculateZoom()
                remote.zoomTo((start * scale).roundToInt())
                // Consommé : le toucher à un doigt (mise au point) ne se déclenche pas
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (start != null) remote.pinchEnded()
    }
}

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
        for (id in quickSettings(r.mode)) {
            val setting = byId[id]?.takeIf { r.isEditable(it) } ?: continue
            // Grisé quand la molette ne permet pas de le changer (la valeur choisie par l'appareil reste affichée)
            AssistChip(
                onClick = { open(Sheet.One(id)) },
                enabled = r.isEditable(setting),
                label = {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(setting.title, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                        Text(r.currentLabel(setting), style = MaterialTheme.typography.labelLarge)
                    }
                },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = Color.White,
                    disabledLabelColor = Color.White.copy(alpha = 0.4f),
                ),
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
                            val usable = r.isEditable(setting)
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
                                enabled = r.isEditable(setting) && r.isEnabled(option),
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
