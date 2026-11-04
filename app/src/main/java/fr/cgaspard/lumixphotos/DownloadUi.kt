package fr.cgaspard.lumixphotos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@Composable
internal fun BottomPanel(s: UiState, vm: CameraSession) {
    s.download?.let { return DownloadProgressCard(it, vm) }
    BottomAppBar(
        actions = {
            if (!s.selectionMode) {
                FilledTonalButton(onClick = vm::enterSelection, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.Outlined.Circle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sélectionner")
                }
                Spacer(Modifier.width(4.dp))
                SelectionMenu(s, vm, label = "Sélection rapide")
            } else {
                SelectionMenu(s, vm)
                if (s.selected.isNotEmpty()) {
                    val sizes = s.items.filter { it.id in s.selected }.map { s.meta[it.title]?.size ?: -1L }
                    val known = sizes.filter { it > 0 }.sum()
                    Text(
                        (if (sizes.any { it <= 0 }) "≥ " else "") + formatSize(known),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
        floatingActionButton = {
            if (s.selectionMode && s.selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = vm::prepareDownload,
                    icon = { Icon(Icons.Default.Download, null) },
                    text = { Text("Télécharger") },
                    elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                )
            }
        },
    )
}

@Composable
private fun SelectionMenu(s: UiState, vm: CameraSession, label: String = "Sélection") {
    var open by remember { mutableStateOf(false) }
    val visible = s.visibleItems
    val photos = visible.count { !it.isVideo }
    val videos = visible.size - photos
    val fresh = visible.count { !s.isDownloaded(it) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(label)
            Icon(Icons.Default.ArrowDropDown, null)
        }
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
                "Astuce : appui long sur un fichier pour le sélectionner, puis glisser pour en sélectionner toute une série",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(220.dp),
            )
        }
    }
}

/** Carte de progression : anneau pour l'ensemble, barre pour le fichier en cours. */
@Composable
private fun DownloadProgressCard(d: DownloadProgress, vm: CameraSession) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { d.totalFraction },
                        modifier = Modifier.size(48.dp),
                        trackColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                    Text("${(d.totalFraction * 100).toInt()} %", style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        "Fichier ${d.index} sur ${d.count}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        d.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalIconButton(onClick = vm::cancelDownload) { Icon(Icons.Default.Close, "Annuler") }
            }
            if (d.totalBytes > 0) {
                LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val done = d.doneBytes + d.bytes
            val details = buildString {
                append(formatSize(done))
                if (d.plannedBytes > 0) append(" / ${formatSize(d.plannedBytes)}")
                if (d.bytesPerSecond > 0) {
                    append(" · ${formatSize(d.bytesPerSecond)}/s")
                    if (d.plannedBytes > done) append(" · reste ~${formatDuration((d.plannedBytes - done) / d.bytesPerSecond)}")
                }
            }
            Text(
                d.retrying ?: details,
                style = MaterialTheme.typography.bodySmall,
                color = if (d.retrying != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DownloadDialog(plan: DownloadPlan, battery: Battery?, vm: CameraSession) {
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
        icon = { Icon(Icons.Default.Download, null) },
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
