package fr.cgaspard.lumixphotos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Juste après la connexion : prise de vue, ou parcours de la carte SD (lue seulement dans ce cas). */
@Composable
internal fun ChooserScreen(s: UiState, vm: CameraSession, message: String? = null) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        val landscape = maxWidth > maxHeight
        Column(
            Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Connecté à l'appareil", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.wifiSsid, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.battery?.let { BatteryIcon(it, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(start = 10.dp)) }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            val remote = @Composable { mod: Modifier ->
                Choice(Icons.Outlined.PhotoCamera, "Prise de vue", "Visée en direct, déclencheur, zoom et réglages", mod, vm::enterRemote)
            }
            val gallery = @Composable { mod: Modifier ->
                Choice(Icons.Outlined.PhotoLibrary, "Photos et vidéos", "Parcourir la carte SD et télécharger", mod, vm::openGallery)
            }
            if (landscape) {
                // Même hauteur pour les deux cartes
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    remote(Modifier.weight(1f).fillMaxHeight())
                    gallery(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                remote(Modifier.fillMaxWidth())
                gallery(Modifier.fillMaxWidth())
            }
            Text(
                "Vous pourrez passer de l'un à l'autre à tout moment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = vm::disconnect) {
                Icon(Icons.Default.LinkOff, null)
                Text("  Déconnecter")
            }
        }
    }
}

@Composable
private fun Choice(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
