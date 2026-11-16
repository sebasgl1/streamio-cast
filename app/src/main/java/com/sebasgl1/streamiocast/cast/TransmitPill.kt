package com.sebasgl1.streamiocast.cast

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebasgl1.streamiocast.R
import com.sebasgl1.streamiocast.StremioApp

/**
 * Botón de transmitir para las pantallas de la app (inicio, ficha, streams): eliges el Chromecast o
 * el TV antes de reproducir y la lista de streams se filtra sola para ese aparato.
 * Conectado, muestra el nombre del aparato resaltado.
 */
@Composable
fun TransmitPill(modifier: Modifier = Modifier, overImage: Boolean = false) {
    val app = LocalContext.current.applicationContext as StremioApp
    val device by app.selectedDevice.collectAsState()
    var open by remember { mutableStateOf(false) }
    val connected = device != null
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        onClick = { open = true },
        shape = RoundedCornerShape(50),
        color = when {
            connected -> accent.copy(alpha = 0.18f)
            overImage -> Color.Black.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (connected) BorderStroke(1.dp, accent.copy(alpha = 0.7f)) else null,
        modifier = modifier.animateContentSize(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_cast),
                contentDescription = null,
                tint = if (connected) accent else if (overImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = device?.name ?: "Transmitir",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (connected) accent else if (overImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp),
            )
        }
    }

    if (open) {
        DevicePickerDialog(
            connectedTo = device?.name,
            onChromecast = {
                open = false
                app.selectChromecast()
            },
            onDlna = {
                open = false
                app.selectDlna(it)
            },
            onStop = {
                open = false
                app.stopDevice()
            },
            onDismiss = { open = false },
        )
    }
}
