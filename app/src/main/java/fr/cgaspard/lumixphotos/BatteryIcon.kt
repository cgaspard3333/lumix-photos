package fr.cgaspard.lumixphotos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Batterie de l'appareil dessinée comme sur son écran : une barre par niveau (ex. 3 barres sur 3). */
@Composable
fun BatteryIcon(battery: Battery, color: Color, modifier: Modifier = Modifier) {
    val tint = if (battery.low) MaterialTheme.colorScheme.error else color
    Row(
        modifier.semantics { contentDescription = "Batterie ${battery.level} sur ${battery.max}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .size(width = 26.dp, height = 13.dp)
                .border(1.5.dp, tint, RoundedCornerShape(3.dp))
                .padding(2.5.dp),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        ) {
            repeat(battery.max.coerceIn(1, 5)) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (i < battery.level) tint else Color.Transparent, RoundedCornerShape(1.dp)),
                )
            }
        }
        // Borne
        Box(Modifier.width(2.dp).size(width = 2.dp, height = 6.dp).background(tint, RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp)))
    }
}
