@file:OptIn(ExperimentalMaterial3Api::class)

package com.sebasgl1.streamiocast.ui

import com.sebasgl1.streamiocast.data.matchesQuery
import com.sebasgl1.streamiocast.data.languageLabel
import com.sebasgl1.streamiocast.data.languageFamilies
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import com.sebasgl1.streamiocast.data.StreamSort
import com.sebasgl1.streamiocast.data.key
import com.sebasgl1.streamiocast.data.withVerified
import com.sebasgl1.streamiocast.data.arrangeStreams
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
    var sort by rememberSaveable {
        mutableStateOf(runCatching { StreamSort.valueOf(prefs.streamSort) }.getOrDefault(StreamSort.RECOMMENDED))
    }
    var descending by rememberSaveable { mutableStateOf(prefs.streamSortDescending) }
    var language by rememberSaveable { mutableStateOf(prefs.streamLanguage) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<StreamResult?>(null) }

    // Al elegir (o cambiar) el aparato, la lista se filtra sola para él. Solo cuando cambia: al
    // volver del reproductor se respeta el filtro que habías puesto (por ejemplo "Todos").
    var appliedDevice by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(device?.key) {
        val key = device?.key
        if (key != null && key != appliedDevice) {
            filter = if (device is TargetDevice.Tv) StreamFilter.TV else StreamFilter.CHROMECAST
        }
        appliedDevice = key
    }

    // Colores según el aparato real (marca, año, modelo) y lo aprendido con "No tiene sonido".
    val castDevice = device as? TargetDevice.Chromecast
    val tvDevice = device as? TargetDevice.Tv
    // Lo que ya se probó de verdad en el aparato manda sobre lo que se adivina por el nombre.
    val learned by app.compat.changes.collectAsStateWithLifecycle()
    val results = remember(state.results, device, learned) {
        val castProfile = app.compat.castProfile(castDevice)
        val tvProfile = app.compat.tvProfile(tvDevice)
        state.results.map { r ->
            val k = r.key
            val castSeen = if (k != null) castDevice?.let { app.compat.verified(it.key, k) } else null
            val tvSeen = if (k != null) tvDevice?.let { app.compat.verified(it.key, k) } else null
            r.copy(
                cls = classify(r.info, castProfile).withVerified(castSeen),
                tv = classifyTv(r.info, tvProfile).withVerified(tvSeen),
            )
        }
    }
    val castLabel = castDevice?.name ?: "Chromecast"
    val tvLabel = tvDevice?.name ?: "TV"

    // Idiomas que aparecen en esta lista, del más común al menos (para los chips).
    val languages = remember(results) {
        results.flatMap { it.info.languageFamilies }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
    }
    // Idioma y búsqueda primero; luego el filtro del aparato y el orden, todos juntos.
    val byLanguage = remember(results, language, query) {
        results.filter { r ->
            (language == "ALL" || language in r.info.languageFamilies) && r.matchesQuery(query)
        }
    }
    val visible = remember(byLanguage, filter, sort, descending) { arrangeStreams(byLanguage, filter, sort, descending) }
    val ccCount = byLanguage.count { it.cls.verdict != Verdict.BAD }
    val tvCount = byLanguage.count { it.tv.verdict != Verdict.BAD }

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
                    text = when (filter) {
                        StreamFilter.ALL -> "Todos los streams de tus addons"
                        StreamFilter.CHROMECAST -> "Los que sirven en $castLabel"
                        StreamFilter.TV -> "Los que sirven en $tvLabel"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (filter != StreamFilter.ALL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                searching = !searching
                if (!searching) query = ""
            }) {
                Icon(Icons.Filled.Search, contentDescription = "Buscar en la lista")
            }
            TransmitPill(modifier = Modifier.padding(end = 4.dp))
        }

        // Búsqueda libre: "latino", "castellano dual", "1080"…
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("Buscar: latino, castellano, dual, 1080…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Borrar") }
                    }
                },
            )
        }

        // Idioma: solo los que aparecen en esta lista. Latino y castellano cuentan como español.
        if (languages.isNotEmpty() || language != "ALL") {
            LazyRow(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    Text(
                        "Idioma",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val options = listOf("ALL") + (languages + listOfNotNull(language.takeIf { it != "ALL" })).distinct()
                items(options) { code ->
                    val count = if (code == "ALL") results.size else results.count { code in it.info.languageFamilies }
                    FilterChip(
                        selected = language == code,
                        onClick = {
                            language = code
                            prefs.streamLanguage = code
                        },
                        label = {
                            Text(
                                (if (code == "ALL") "Todos" else languageLabel(code)) + " ($count)",
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }

        // Filtro: qué streams sirven en el aparato al que vas a transmitir.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val loaded = !state.loading && results.isNotEmpty()
            val chips = listOf(
                StreamFilter.ALL to "Todos" + if (loaded) " (${byLanguage.size})" else "",
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

        // Orden: tocar el criterio activo invierte el sentido (↓ mayor primero, ↑ menor primero).
        LazyRow(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                Text(
                    "Ordenar",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(StreamSort.entries) { option ->
                val active = sort == option
                val arrow = when {
                    option == StreamSort.RECOMMENDED -> ""
                    active -> if (descending) " ↓" else " ↑"
                    else -> if (option.defaultDescending) " ↓" else " ↑"
                }
                FilterChip(
                    selected = active,
                    onClick = {
                        if (active && option != StreamSort.RECOMMENDED) {
                            descending = !descending
                        } else {
                            sort = option
                            descending = option.defaultDescending
                        }
                        prefs.streamSort = sort.name
                        prefs.streamSortDescending = descending
                    },
                    label = { Text(option.label + arrow, maxLines = 1) },
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
                    val hiddenByLanguage = state.message == null && results.isNotEmpty() && byLanguage.isEmpty()
                    val emptyMessage = state.message
                        ?: if (hiddenByLanguage) {
                            val what = listOfNotNull(
                                language.takeIf { it != "ALL" }?.let { "en ${languageLabel(it).lowercase()}" },
                                query.takeIf { it.isNotBlank() }?.let { "con “$it”" },
                            ).joinToString(" ")
                            "No hay streams $what para este título. Los addons no siempre indican el idioma: " +
                                "prueba con \"Todos\" y elige el audio en el reproductor."
                        } else if (state.results.isNotEmpty()) {
                            "Ninguno de los ${results.size} streams sirve directo en " +
                                (if (filter == StreamFilter.TV) tvLabel else castLabel) +
                                ". Con \"Ver todos\" puedes elegir uno igual: si el audio o el vídeo no " +
                                "son compatibles, la app ofrece convertirlo en el celular."
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
                        if (hiddenByLanguage) {
                            Button(onClick = {
                                language = "ALL"
                                prefs.streamLanguage = "ALL"
                                query = ""
                            }) { Text("Ver todos los idiomas") }
                        } else if (state.message == null && state.results.isNotEmpty()) {
                            // Hay streams, solo que el filtro los oculta: mostrarlos, no volver a buscar.
                            Button(onClick = {
                                filter = StreamFilter.ALL
                                prefs.streamFilter = StreamFilter.ALL.name
                            }) { Text("Ver todos") }
                        } else {
                            Button(onClick = vm::load) { Text("Reintentar") }
                        }
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
    // Idiomas aparte, bien visibles: es lo primero que buscan quienes solo ven en español.
    val languageText = info.languages.joinToString(" · ") { languageLabel(it) }
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
            if (languageText.isNotEmpty()) {
                Text(
                    text = "🗣 $languageText",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
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
