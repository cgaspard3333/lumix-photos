package fr.cgaspard.lumixphotos

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectScreen(s: UiState, vm: CameraSession) {
    val working = s.connection as? ConnectionState.Working
    var ssid by remember(s.wifiSsid) { mutableStateOf(s.wifiSsid) }
    var password by remember(s.wifiPassword) { mutableStateOf(s.wifiPassword) }
    var manual by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Téléphone en paysage : présentation à gauche, boutons à droite, sans défilement
        val sideBySide = maxWidth > maxHeight && maxHeight < 560.dp
        val header = @Composable { ConnectHeader(compact = sideBySide, showSteps = working == null) }
        val actions = @Composable {
            ConnectActions(
                s, vm, working, ssid, password, manual, scanError,
                onSsid = { ssid = it }, onPassword = { password = it },
                onManual = { manual = !manual }, onScanError = { scanError = it },
            )
        }
        if (sideBySide) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { header() }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { actions() }
            }
        } else {
            // Sur tablette, contenu centré et limité en largeur plutôt qu'étiré d'un bord à l'autre
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .wrapContentWidth()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                header()
                actions()
            }
        }
    }
}

@Composable
private fun ConnectHeader(compact: Boolean, showSteps: Boolean) {
    val logo = @Composable { size: Int ->
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.PhotoCamera, null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size((size / 2).dp),
                )
            }
        }
    }
    if (compact) {
        // Paysage sur téléphone : logo et titre sur une ligne, sans phrase d'accroche
        Row(verticalAlignment = Alignment.CenterVertically) {
            logo(48)
            Spacer(Modifier.width(12.dp))
            Text("Lumix Photos", style = MaterialTheme.typography.headlineSmall)
        }
    } else {
        logo(112)
        Text("Lumix Photos", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Récupérez les photos et vidéos de votre appareil en Wi-Fi.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
        if (showSteps) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(if (compact) 14.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp)) {
                    Step(1, "Sur l'appareil : MENU › Wi-Fi › Fonction Wi-Fi › Nouvelle connexion › « Prise de vue à distance et affichage ».")
                    Step(2, "Scannez le QR code qui s'affiche alors sur l'écran de l'appareil.")
                }
            }
        }
}

@Composable
private fun ConnectActions(
    s: UiState,
    vm: CameraSession,
    working: ConnectionState.Working?,
    ssid: String,
    password: String,
    manual: Boolean,
    scanError: String?,
    onSsid: (String) -> Unit,
    onPassword: (String) -> Unit,
    onManual: () -> Unit,
    onScanError: (String?) -> Unit,
) {
        val context = LocalContext.current
        val connect = { vm.setWifi(ssid, password); vm.connect() }
        if (working != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(working.message, textAlign = TextAlign.Center)
                }
            }
            return
        }

        Button(
            onClick = {
                onScanError(null)
                WifiQr.scan(
                    context,
                    onResult = { credentials ->
                        if (credentials == null) {
                            onScanError("Ce QR code ne contient pas d'identifiants Wi-Fi.")
                        } else {
                            onSsid(credentials.ssid)
                            onPassword(credentials.password)
                            vm.setWifi(credentials.ssid, credentials.password)
                            vm.connect()
                        }
                    },
                    onError = { onScanError(it) },
                )
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Default.QrCodeScanner, null)
            Spacer(Modifier.width(8.dp))
            Text("Scanner le QR code", style = MaterialTheme.typography.titleMedium)
        }
        // Identifiants déjà mémorisés, ou téléphone déjà connecté au Wi-Fi de l'appareil
        FilledTonalButton(onClick = connect, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Wifi, null)
            Spacer(Modifier.width(8.dp))
            Text(if (s.wifiSsid.isNotEmpty()) "Se connecter à ${s.wifiSsid}" else "Se connecter")
        }

        val error = scanError ?: (s.connection as? ConnectionState.Failed)?.message
        if (error != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        TextButton(onClick = onManual) {
            Text("Réglages manuels")
            Icon(if (manual) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        AnimatedVisibility(manual) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = onSsid,
                    label = { Text("Wi-Fi de l'appareil (ex. FZ1000-A1B2C3)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPassword,
                    label = { Text("Mot de passe (affiché sur l'appareil)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.hostInput,
                    onValueChange = vm::setHostInput,
                    label = { Text("Adresse IP (vide = recherche auto, $DIRECT_MODE_IP en direct)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    }
}
