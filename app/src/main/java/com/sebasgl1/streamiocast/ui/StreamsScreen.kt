@file:OptIn(ExperimentalMaterial3Api::class)

package com.sebasgl1.streamiocast.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sebasgl1.streamiocast.StremioApp
import androidx.compose.ui.platform.LocalContext
import com.sebasgl1.streamiocast.cast.isOnMobileData
import com.sebasgl1.streamiocast.data.AppJson
import com.sebasgl1.streamiocast.history.WatchTarget
import com.sebasgl1.streamiocast.player.PlaybackRequest
import com.sebasgl1.streamiocast.player.toPlayback
import com.sebasgl1.streamiocast.data.Classification
import com.sebasgl1.streamiocast.data.classify
import com.sebasgl1.streamiocast.data.classifyTv
import com.sebasgl1.streamiocast.cast.TargetDevice
import com.sebasgl1.streamiocast.cast.TransmitPill
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.LaunchedEffect
import com.sebasgl1.streamiocast.data.StreamFilter
import com.sebasgl1.streamiocast.data.StreamResult
import com.sebasgl1.streamiocast.data.streamComparator
import com.sebasgl1.streamiocast.data.verdictFor
import com.sebasgl1.streamiocast.data.Verdict
import com.sebasgl1.streamiocast.data.VideoCodec
import com.sebasgl1.streamiocast.data.resolutionLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StreamsState(
    val loading: Boolean = true,
    val results: List<StreamResult> = emptyList(),
    val failures: List<String> = emptyList(),
    val message: String? = null,
)

class StreamsViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = (app as StremioApp).repo
    val target: WatchTarget = runCatching {
        AppJson.decodeFromString(WatchTarget.serializer(), decodeArg(handle["target"] ?: ""))
    }.getOrElse { WatchTarget(videoId = "", metaId = "", type = "movie", name = "Streams") }
    val type: String = target.type
    val id: String = target.videoId
    val title: String = target.displayTitle

    private val _state = MutableStateFlow(StreamsState())
    val state: StateFlow<StreamsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = StreamsState(loading = true)
            val outcome = runCatching { repo.fetchStreams(type, id) }.getOrNull()
            _state.value = when {
                outcome == null -> StreamsState(
                    loading = false,
                    message = "No se pudieron pedir los streams. Revisa tu conexión.",
                )
                outcome.queried == 0 -> StreamsState(
                    loading = false,
                    message = "Ningún addon instalado ofrece streams para este título. " +
                        "Instala Torrentio en Ajustes.",
                )
                else -> StreamsState(
                    loading = false,
                    results = outcome.results,
                    failures = outcome.failures,
                )
            }
        }
    }
}

@Composable
fun StreamsScreen(
    onBack: () -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
    vm: StreamsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as StremioApp
    val prefs = app.playbackPrefs
    val device by app.selectedDevice.collectAsStateWithLifecycle()
    var filter by rememberSaveable {
        mutableStateOf(runCatching { StreamFilter.valueOf(prefs.streamFilter) }.getOrDefault(StreamFilter.CHROMECAST))
    }
    var selected by remember { mutableStateOf<StreamResult?>(null) }

    // Al elegir (o cambiar) el aparato, la lista se filtra sola para él.
    LaunchedEffect(device?.key) {
        when (device) {
            is TargetDevice.Chromecast -> filter = StreamFilter.CHROMECAST
            is TargetDevice.Tv -> filter = StreamFilter.TV
            null -> Unit
        }
    }

    // Colores según el aparato real (marca, año, modelo) y lo aprendido con "No tiene sonido".
    val castDevice = device as? TargetDevice.Chromecast
    val tvDevice = device as? TargetDevice.Tv
    val results = remember(state.results, device) {
        val castProfile = app.compat.castProfile(castDevice)
        val tvProfile = app.compat.tvProfile(tvDevice)
        state.results.map { it.copy(cls = classify(it.info, castProfile), tv = classifyTv(it.info, tvProfile)) }
    }
    val castLabel = castDevice?.name ?: "Chromecast"
    val tvLabel = tvDevice?.name ?: "TV"

    val visible = remember(results, filter) {
        val list = if (filter == StreamFilter.ALL) {
            results
        } else {
            results.filter { it.verdictFor(filter) != Verdict.BAD }
        }
        list.sortedWith(streamComparator(filter))
    }
    val ccCount = results.count { it.cls.verdict != Verdict.BAD }
    val tvCount = results.count { it.tv.verdict != Verdict.BAD }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = vm.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device?.let { "Filtrados para ${it.name}" } ?: "Streams de tus addons",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TransmitPill(modifier = Modifier.padding(end = 4.dp))
        }

        // Filtro: qué streams sirven en el aparato al que vas a transmitir.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val loaded = !state.loading && results.isNotEmpty()
            val chips = listOf(
                StreamFilter.ALL to "Todos" + if (loaded) " (${results.size})" else "",
                StreamFilter.CHROMECAST to castLabel + if (loaded) " ($ccCount)" else "",
                StreamFilter.TV to tvLabel + if (loaded) " ($tvCount)" else "",
            )
            items(chips) { (f, label) ->
                FilterChip(
                    selected = filter == f,
                    onClick = {
                        filter = f
                        prefs.streamFilter = f.name
                    },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }

        if (state.failures.isNotEmpty()) {
            Text(
                text = "No respondió: ${state.failures.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.loading -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Buscando streams…")
                }

                visible.isNotEmpty() -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible) { result ->
                        StreamRow(result, castLabel, tvLabel) { selected = result }
                    }
                }

                else -> {
                    val emptyMessage = state.message
                        ?: if (state.results.isNotEmpty()) {
                            "Ninguno de los ${results.size} streams sirve en " +
                                (if (filter == StreamFilter.TV) tvLabel else castLabel) +
                                ". Toca \"Todos\" para verlos."
                        } else {
                            "No se encontraron streams para este título."
                        }
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(emptyMessage, textAlign = TextAlign.Center)
                        Button(onClick = vm::load) { Text("Reintentar") }
                    }
                }
            }
        }
    }

    selected?.let { result ->
        val context = LocalContext.current
        val onMobile = remember(result) { isOnMobileData(context) }
        StreamDialog(
            item = result,
            castLabel = castLabel,
            tvLabel = tvLabel,
            playLabel = device?.let { "Reproducir en ${it.name}" } ?: "Reproducir",
            onMobileData = onMobile,
            onPlay = {
                selected = null
                onPlay(result.toPlayback(vm.target))
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun verdictColor(verdict: Verdict): Color = when (verdict) {
    Verdict.GOOD -> MaterialTheme.colorScheme.secondary
    Verdict.MAYBE -> Color(0xFFFFC857)
    Verdict.BAD -> MaterialTheme.colorScheme.error
}

/** La palabra del aparato en verde (sirve), amarillo (dudoso) o rojo (no sirve). */
@Composable
private fun DeviceBadge(device: String, verdict: Verdict) {
    val color = verdictColor(verdict)
    val symbol = when (verdict) {
        Verdict.GOOD -> "✓"
        Verdict.MAYBE -> "?"
        Verdict.BAD -> "✕"
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "$symbol $device",
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StreamRow(item: StreamResult, castLabel: String, tvLabel: String, onClick: () -> Unit) {
    val info = item.info
    val tags = buildList<String> {
        info.resolutionLabel()?.let { add(it) }
        if (info.codec != VideoCodec.UNKNOWN && info.codec != VideoCodec.MIXED) add(info.codec.label)
        if (info.audio.isNotEmpty()) add(info.audio.joinToString("/") { it.label })
        info.container?.let { add(it.uppercase()) }
    }
    val details = buildList<String> {
        info.seeders?.let { add("👤 $it") }
        info.sizeText?.let { add(it) }
        info.provider?.let { add(it) }
        if (item.stream.url != null && item.stream.infoHash == null) add("Enlace directo")
        add(item.addon)
    }

    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceBadge(shortLabel(castLabel), item.cls.verdict)
                Spacer(Modifier.width(6.dp))
                DeviceBadge(shortLabel(tvLabel), item.tv.verdict)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = info.releaseName.ifBlank { "Sin nombre" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StreamDialog(
    item: StreamResult,
    castLabel: String,
    tvLabel: String,
    playLabel: String,
    onMobileData: Boolean,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onPlay) { Text(playLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = {
            Text(
                text = listOfNotNull(item.info.resolutionLabel(), item.info.sizeText).joinToString(" · ")
                    .ifEmpty { "Stream" },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.info.releaseName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DeviceReasons(castLabel, item.cls)
                DeviceReasons(tvLabel, item.tv)
                if (onMobileData) {
                    Text(
                        text = "Estás con datos móviles" +
                            (item.info.sizeText?.let { ": este stream pesa $it." } ?: ". Un torrent puede gastar varios GB."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "En el celular se puede ver cualquier stream. Antes de mandarlo al televisor, " +
                        "la app revisa el archivo real y te avisa si no es compatible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
    )
}

/** Nombre corto para las etiquetas de la lista ("Samsung 6 Series (43)" → "Samsung 6 Series"). */
private fun shortLabel(name: String): String {
    val clean = name.replace(Regex("""^\[[^]]*]\s*"""), "").replace(Regex("""\s*\([^)]*\)$"""), "").trim()
    return if (clean.length <= 16) clean.ifEmpty { name } else clean.take(15).trimEnd() + "…"
}

@Composable
private fun DeviceReasons(device: String, cls: Classification) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DeviceBadge(device, cls.verdict)
        cls.reasons.forEach { reason ->
            Text("• $reason", style = MaterialTheme.typography.bodySmall)
        }
    }
}
