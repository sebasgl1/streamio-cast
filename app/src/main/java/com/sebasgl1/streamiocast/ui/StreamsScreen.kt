package com.sebasgl1.streamiocast.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
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
import com.sebasgl1.streamiocast.player.PlaybackRequest
import com.sebasgl1.streamiocast.player.toPlayback
import com.sebasgl1.streamiocast.data.StreamResult
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
    private val type: String = Uri.decode(handle["type"] ?: "")
    private val id: String = Uri.decode(handle["id"] ?: "")
    val title: String = decodeArg(handle["title"] ?: "")

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
    var onlyCompatible by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<StreamResult?>(null) }

    val visible = remember(state.results, onlyCompatible) {
        if (onlyCompatible) state.results.filter { it.cls.verdict != Verdict.BAD } else state.results
    }
    val good = state.results.count { it.cls.verdict == Verdict.GOOD }
    val maybe = state.results.count { it.cls.verdict == Verdict.MAYBE }
    val incompatible = state.results.size - good - maybe

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
                    text = "Streams de tus addons",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Solo compatibles con Chromecast", style = MaterialTheme.typography.bodyLarge)
                if (!state.loading && state.results.isNotEmpty()) {
                    Text(
                        text = "$good compatibles · $maybe dudosos · $incompatible no compatibles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = onlyCompatible, onCheckedChange = { onlyCompatible = it })
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
                        StreamRow(result) { selected = result }
                    }
                }

                else -> {
                    val emptyMessage = state.message
                        ?: if (state.results.isNotEmpty()) {
                            "Todos los streams (${state.results.size}) son incompatibles con tu Chromecast. " +
                                "Desactiva el filtro para verlos."
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
        StreamDialog(
            item = result,
            onPlay = {
                selected = null
                onPlay(result.toPlayback(vm.title))
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

@Composable
private fun VerdictChip(verdict: Verdict) {
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
            text = "$symbol ${verdict.label}",
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StreamRow(item: StreamResult, onClick: () -> Unit) {
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
                VerdictChip(item.cls.verdict)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
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
private fun StreamDialog(item: StreamResult, onPlay: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onPlay) { Text("Reproducir aquí") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = { Text(item.cls.verdict.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = item.info.releaseName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.cls.reasons.forEach { reason ->
                    Text("• $reason", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "En el celular se puede ver cualquier stream, incluso los no compatibles. " +
                        "Transmitir al Chromecast llega en la etapa 4.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
    )
}
