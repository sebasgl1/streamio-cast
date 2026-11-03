package com.sebasgl1.streamiocast.player

import android.app.Activity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.sebasgl1.streamiocast.torrent.TorrentStats
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, vm: PlayerViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    ImmersiveMode()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val phase = ui.phase) {
            PlayerPhase.Playing -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = vm.player
                            keepScreenOn = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
                val stats = ui.stats
                if (ui.buffering && stats != null) {
                    Text(
                        text = "Cargando · ${statsLine(stats)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 96.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            is PlayerPhase.Preparing -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator()
                Text(ui.title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(phase.message, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                if (ui.isTorrent) {
                    Text(
                        text = "La primera vez puede tardar: hay que encontrar peers y bajar el inicio del vídeo.",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            is PlayerPhase.Failed -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(phase.message, color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = onBack) { Text("Volver a los streams") }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
        }
    }
}

private fun statsLine(s: TorrentStats): String =
    "${s.peers} peers · ${formatRate(s.downloadRate)} · ${(s.progress * 100).toInt()}%"

private fun formatRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    else -> "${bytesPerSecond / 1024} KB/s"
}

/** Pantalla completa mientras se ve el vídeo; al salir vuelven las barras del sistema. */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
