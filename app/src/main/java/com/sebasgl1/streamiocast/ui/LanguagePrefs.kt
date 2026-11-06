@file:OptIn(ExperimentalMaterial3Api::class)

package com.sebasgl1.streamiocast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sebasgl1.streamiocast.StremioApp
import com.sebasgl1.streamiocast.data.PlaybackPrefs

private val LANGUAGES = listOf(
    "es" to "Español",
    "en" to "Inglés",
    "pt" to "Portugués",
    "fr" to "Francés",
    "it" to "Italiano",
    "de" to "Alemán",
    "ja" to "Japonés",
)
private val AUDIO_OPTIONS = listOf(PlaybackPrefs.ORIGINAL to "Original") + LANGUAGES
private val SUBTITLE_OPTIONS = listOf(PlaybackPrefs.OFF to "Desactivados") + LANGUAGES

/** Ajustes de idioma: qué audio y qué subtítulos elegir solos al reproducir. */
@Composable
fun LanguagePrefs() {
    val prefs = (LocalContext.current.applicationContext as StremioApp).playbackPrefs
    var audio by remember { mutableStateOf(prefs.audioLang) }
    var subs by remember { mutableStateOf(prefs.subtitleLang) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Text("Idiomas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Audio preferido", style = MaterialTheme.typography.titleSmall)
        ChipRow(AUDIO_OPTIONS, audio) {
            audio = it
            prefs.audioLang = it
        }
        Text("Subtítulos preferidos", style = MaterialTheme.typography.titleSmall)
        ChipRow(SUBTITLE_OPTIONS, subs) {
            subs = it
            prefs.subtitleLang = it
        }
        Text(
            text = "Los subtítulos se activan solos cuando el audio no está en ese idioma. " +
                "Durante el vídeo puedes cambiarlos en \"Audio y subtítulos\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val START_OPTIONS = listOf(
    PlaybackPrefs.START_AUTO.toString() to "Automático",
    PlaybackPrefs.START_BUFFER.toString() to "Esperar buffer",
    PlaybackPrefs.START_MANUAL.toString() to "Manual",
)

/** Cómo arranca el vídeo: enseguida, al tener N segundos descargados, o cuando le des reproducir. */
@Composable
fun StartPrefs() {
    val prefs = (LocalContext.current.applicationContext as StremioApp).playbackPrefs
    var mode by remember { mutableStateOf(prefs.startMode) }
    var seconds by remember { mutableStateOf(prefs.bufferSeconds.toFloat()) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Text("Inicio de reproducción", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ChipRow(START_OPTIONS, mode.toString()) {
            mode = it.toInt()
            prefs.startMode = mode
        }
        if (mode != PlaybackPrefs.START_AUTO) {
            Text("Buffer: ${seconds.toInt()} s de vídeo", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = seconds,
                onValueChange = { seconds = it },
                onValueChangeFinished = { prefs.bufferSeconds = seconds.toInt() },
                valueRange = 10f..60f,
                steps = 9,
            )
        }
        Text(
            text = when (mode) {
                PlaybackPrefs.START_AUTO -> "Arranca apenas hay algo descargado, como Stremio."
                PlaybackPrefs.START_BUFFER -> "Espera a tener ${seconds.toInt()} s de vídeo descargados y arranca solo. " +
                    "Puedes darle \"Reproducir ya\" antes."
                else -> "Descarga por delante y arranca cuando le des Reproducir."
            } + if (mode != PlaybackPrefs.START_AUTO) {
                " Si a mitad del vídeo se acaba lo descargado, pausa y espera otra vez esos segundos en vez de trabarse."
            } else {
                ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { (code, name) ->
            FilterChip(
                selected = code == selected,
                onClick = { onSelect(code) },
                label = { Text(name) },
            )
        }
    }
}
