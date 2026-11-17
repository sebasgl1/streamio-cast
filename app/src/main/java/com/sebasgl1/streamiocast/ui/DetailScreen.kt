@file:OptIn(ExperimentalMaterial3Api::class)

package com.sebasgl1.streamiocast.ui

import com.sebasgl1.streamiocast.cast.TransmitPill

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import com.sebasgl1.streamiocast.StremioApp
import com.sebasgl1.streamiocast.history.WatchEntry
import com.sebasgl1.streamiocast.history.WatchTarget
import com.sebasgl1.streamiocast.history.continueWatching
import com.sebasgl1.streamiocast.history.resumePosition
import com.sebasgl1.streamiocast.data.MetaItem
import com.sebasgl1.streamiocast.data.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailState(
    val loading: Boolean = true,
    val meta: MetaItem? = null,
    val error: String? = null,
)

class DetailViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = (app as StremioApp).repo
    private val type: String = handle["type"] ?: ""
    private val id: String = handle["id"] ?: ""

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = DetailState(loading = true)
            val meta = runCatching { repo.fetchMeta(type, id) }.getOrNull()
            _state.value = if (meta != null) {
                DetailState(loading = false, meta = meta)
            } else {
                DetailState(loading = false, error = "No se encontró información de este título.")
            }
        }
    }
}

@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onStreams: (WatchTarget) -> Unit,
    vm: DetailViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val meta = state.meta
    val app = LocalContext.current.applicationContext as StremioApp
    val history by app.history.entries.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        when {
            meta != null -> DetailContent(meta, history, onStreams)

            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            else -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(state.error ?: "Error", textAlign = TextAlign.Center)
                Button(onClick = vm::load) { Text("Reintentar") }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
        }

        TransmitPill(
            overImage = true,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )
    }
}

@Composable
private fun DetailContent(
    meta: MetaItem,
    history: Map<String, WatchEntry>,
    onStreams: (WatchTarget) -> Unit,
) {
    val seasons = remember(meta) { meta.videos.mapNotNull { it.season }.distinct().sorted() }
    // Lo que sale en "Continuar viendo" para esta serie: el episodio a medias o el siguiente.
    val resume = remember(history, meta.id) {
        continueWatching(history.values, limit = Int.MAX_VALUE).firstOrNull { it.target.metaId == meta.id }
    }
    val resumeVideo = remember(meta, resume) { resume?.let { r -> meta.videos.firstOrNull { it.id == r.target.videoId } } }
    var season by rememberSaveable(meta.id) {
        mutableStateOf(resumeVideo?.season ?: seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 0)
    }
    val episodes = remember(meta, season) {
        meta.videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    }
    val openMovie: () -> Unit = {
        onStreams(
            WatchTarget(
                videoId = meta.id,
                metaId = meta.id,
                type = meta.type.ifEmpty { "movie" },
                name = meta.name,
                poster = meta.poster,
            ),
        )
    }
    val openEpisode: (Video) -> Unit = { video ->
        onStreams(
            WatchTarget(
                videoId = video.id,
                metaId = meta.id,
                type = meta.type.ifEmpty { "series" },
                name = meta.name,
                poster = meta.poster,
                season = video.season ?: 0,
                episode = video.episode ?: 0,
                episodeTitle = video.label,
            ),
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp

        if (wide && seasons.isNotEmpty()) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(start = 24.dp, top = 64.dp, end = 24.dp, bottom = 24.dp),
                ) {
                    item { InfoBlock(meta, wide = true) }
                    if (resumeVideo != null && resume != null) {
                        item { ResumeEpisode(resume, resumeVideo) { openEpisode(resumeVideo) } }
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(start = 8.dp, top = 24.dp, end = 24.dp, bottom = 24.dp),
                ) {
                    episodesSection(seasons, season, { season = it }, episodes, history, openEpisode)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 64.dp, end = 16.dp, bottom = 24.dp),
            ) {
                item { InfoBlock(meta, wide) }
                if (seasons.isNotEmpty() && resumeVideo != null && resume != null) {
                    item { ResumeEpisode(resume, resumeVideo) { openEpisode(resumeVideo) } }
                }
                if (seasons.isNotEmpty()) {
                    episodesSection(seasons, season, { season = it }, episodes, history, openEpisode)
                } else {
                    item {
                        val entry = history[meta.id]
                        Column(Modifier.padding(top = 20.dp)) {
                            WatchProgress(entry, Modifier.padding(bottom = 10.dp))
                            Button(onClick = openMovie) {
                                Text(if (resumePosition(entry) != null) "Continuar" else "Ver streams")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Continuar · T1 E3": un toque para seguir la serie sin buscar el episodio en la lista. */
@Composable
private fun ResumeEpisode(entry: WatchEntry, video: Video, onClick: () -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        WatchProgress(entry, Modifier.padding(bottom = 10.dp))
        Button(onClick = onClick) {
            val what = "T${video.season ?: 0} E${video.episode ?: 0}"
            Text(if (resumePosition(entry) != null) "Continuar · $what" else "Ver siguiente · $what")
        }
    }
}

@Composable
private fun InfoBlock(meta: MetaItem, wide: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = meta.poster,
                contentDescription = meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(if (wide) 200.dp else 120.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = meta.name,
                    style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                val facts = listOfNotNull(
                    meta.releaseInfo,
                    meta.runtime,
                    meta.imdbRating?.let { "★ $it" },
                ).joinToString("  •  ")
                if (facts.isNotEmpty()) {
                    Text(
                        text = facts,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (meta.genres.isNotEmpty()) {
                    Text(
                        text = meta.genres.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (!meta.description.isNullOrBlank()) {
            Text(
                text = meta.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

private fun LazyListScope.episodesSection(
    seasons: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    episodes: List<Video>,
    history: Map<String, WatchEntry>,
    onEpisode: (Video) -> Unit,
) {
    item {
        Column {
            Text(
                text = "Episodios",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { s ->
                    FilterChip(
                        selected = s == selected,
                        onClick = { onSelect(s) },
                        label = { Text(if (s == 0) "Especiales" else "Temporada $s") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    items(episodes) { video ->
        EpisodeRow(video, history[video.id]) { onEpisode(video) }
    }
}

@Composable
private fun EpisodeRow(video: Video, entry: WatchEntry?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = video.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "${video.episode ?: "?"}. ${video.label}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            video.released?.take(10)?.let { date ->
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WatchProgress(entry, Modifier.padding(top = 4.dp))
        }
    }
}
