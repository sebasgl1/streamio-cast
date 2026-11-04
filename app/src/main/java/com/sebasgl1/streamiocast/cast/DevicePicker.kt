package com.sebasgl1.streamiocast.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.sebasgl1.streamiocast.R
import com.sebasgl1.streamiocast.dlna.DlnaDevice
import com.sebasgl1.streamiocast.dlna.DlnaDiscovery

/**
 * Botón "Transmitir": una sola lista con tus Chromecasts y tus televisores DLNA (como un Samsung
 * sin Chromecast). Si ya estás transmitiendo, ofrece dejar de hacerlo.
 */
@Composable
fun TransmitButton(
    connectedTo: String?,
    onChromecast: () -> Unit,
    onDlna: (DlnaDevice) -> Unit,
    onStop: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_cast),
            contentDescription = "Transmitir",
            tint = if (connectedTo != null) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
    if (open) {
        DevicePickerDialog(
            connectedTo = connectedTo,
            onChromecast = {
                open = false
                onChromecast()
            },
            onDlna = {
                open = false
                onDlna(it)
            },
            onStop = {
                open = false
                onStop()
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun DevicePickerDialog(
    connectedTo: String?,
    onChromecast: () -> Unit,
    onDlna: (DlnaDevice) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // Chromecasts: los encuentra el propio sistema de Google Cast (MediaRouter).
    val router = remember { MediaRouter.getInstance(context) }
    val selector = remember { runCatching { CastContext.getSharedInstance(context).mergedSelector }.getOrNull() }
    var castRoutes by remember { mutableStateOf(emptyList<MediaRouter.RouteInfo>()) }
    DisposableEffect(selector) {
        if (selector == null) return@DisposableEffect onDispose {}
        fun refresh() {
            castRoutes = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
        }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        onDispose { router.removeCallback(callback) }
    }

    // Televisores DLNA: los buscamos nosotros (SSDP).
    var dlnaDevices by remember { mutableStateOf(emptyList<DlnaDevice>()) }
    var searching by remember { mutableStateOf(true) }
    var searchRound by remember { mutableIntStateOf(0) }
    LaunchedEffect(searchRound) {
        searching = true
        dlnaDevices = DlnaDiscovery.discover(context)
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transmitir a…") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (connectedTo != null) {
                    Text(
                        text = "Transmitiendo a $connectedTo",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = onStop) { Text("Dejar de transmitir y seguir en el celular") }
                }

                Section("Chromecast")
                if (castRoutes.isEmpty()) {
                    Hint(if (selector == null) "Google Cast no está disponible en este celular." else "Buscando…")
                }
                castRoutes.forEach { route ->
                    DeviceRow(name = route.name, detail = route.description?.toString()) {
                        onChromecast()
                        route.select()
                    }
                }

                Section("Televisores (DLNA)")
                dlnaDevices.forEach { device ->
                    DeviceRow(name = device.name, detail = listOf(device.manufacturer, device.model).filter { it.isNotBlank() }.joinToString(" · ")) {
                        onDlna(device)
                    }
                }
                if (searching) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Buscando televisores en tu Wi-Fi…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (dlnaDevices.isEmpty()) {
                    Hint("No encontré televisores. Revisa que estén encendidos y en la misma red Wi-Fi.")
                }
                if (!searching) {
                    TextButton(onClick = { searchRound++ }) { Text("Buscar de nuevo") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeviceRow(name: String, detail: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        if (!detail.isNullOrBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
