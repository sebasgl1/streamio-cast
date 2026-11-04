package com.sebasgl1.streamiocast.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.sebasgl1.streamiocast.StremioApp
import com.sebasgl1.streamiocast.history.continueWatching
import com.sebasgl1.streamiocast.data.Addon
import com.sebasgl1.streamiocast.data.MetaItem
import com.sebasgl1.streamiocast.data.hasResource
import com.sebasgl1.streamiocast.data.requiresExtra
import com.sebasgl1.streamiocast.data.typeLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogRow(val order: Int, val title: String, val items: List<MetaItem>)

data class HomeState(
    val loading: Boolean = true,
    val rows: List<CatalogRow> = emptyList(),
    val error: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as StremioApp).repo
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        start()
    }

    fun retry() = start()

    private fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = HomeState(loading = true)
            val ready = runCatching { repo.ensureDefaults() }
            if (ready.isFailure) {
                _state.value = HomeState(
                    loading = false,
                    error = "No se pudo conectar: ${ready.exceptionOrNull()?.message ?: "error de red"}",
                )
                return@launch
            }
            repo.addons.collectLatest { addons -> loadRows(addons) }
        }
    }

    private suspend fun loadRows(addons: List<Addon>) = coroutineScope {
        val targets = addons
            .filter { it.manifest.hasResource("catalog") }
            .flatMap { addon -> addon.manifest.catalogs.filter { !it.requiresExtra }.map { addon to it } }
            .take(14)

        _state.value = HomeState(
            loading = targets.isNotEmpty(),
            error = if (targets.isEmpty()) "Ningún addon instalado ofrece catálogos. Añade uno en Ajustes." else null,
        )

        targets.mapIndexed { index, (addon, catalog) ->
            async {
                val items = runCatching { repo.fetchCatalog(addon, catalog) }.getOrDefault(emptyList())
                ensureActive()
                if (items.isNotEmpty()) {
                    val title = "${catalog.name ?: catalog.id} · ${typeLabel(catalog.type)}"
                    _state.update { s ->
                        s.copy(rows = (s.rows + CatalogRow(index, title, items)).sortedBy { it.order })
                    }
                }
            }
        }.awaitAll()

        _state.update { s ->
            s.copy(
                loading = false,
                error = if (s.rows.isEmpty() && targets.isNotEmpty()) {
                    "No se pudo cargar contenido. Revisa tu conexión."
                } else {
                    s.error
                },
            )
        }
    }
}

@Composable
fun HomeScreen(
    onOpen: (MetaItem) -> Unit,
    onResumePlayer: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as StremioApp
    val history by app.history.entries.collectAsStateWithLifecycle()
    val continueList = remember(history) { continueWatching(history.values) }

    Box(Modifier.fillMaxSize()) {
        when {
            state.rows.isNotEmpty() || continueList.isNotEmpty() -> LazyColumn(
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (continueList.isNotEmpty()) {
                    item(key = "continuar") {
                        ContinueRow(
                            entries = continueList,
                            onOpen = { meta ->
                                // Si eso es lo que está sonando en el TV, se vuelve al reproductor.
                                val session = app.session
                                if (session != null && session.isOpen && session.request.target.metaId == meta.id) {
                                    onResumePlayer()
                                } else {
                                    onOpen(meta)
                                }
                            },
                            onRemove = { app.history.hideMeta(it) },
                        )
                    }
                }
                items(state.rows, key = { it.order }) { row ->
                    Column {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(row.items) { item ->
                                PosterCard(item, Modifier.width(128.dp)) { onOpen(item) }
                            }
                        }
                    }
                }
            }

            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            else -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = state.error ?: "Sin contenido",
                    textAlign = TextAlign.Center,
                )
                Button(onClick = vm::retry) { Text("Reintentar") }
            }
        }
    }
}
