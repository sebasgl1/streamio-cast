package com.sebasgl1.streamiocast.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sebasgl1.streamiocast.StremioApp
import com.sebasgl1.streamiocast.data.MetaItem
import com.sebasgl1.streamiocast.data.hasResource
import com.sebasgl1.streamiocast.data.supportsSearch
import com.sebasgl1.streamiocast.data.typeLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchSection(val type: String, val items: List<MetaItem>)

data class SearchState(
    val loading: Boolean = false,
    val query: String = "",
    val sections: List<SearchSection> = emptyList(),
    val searched: Boolean = false,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as StremioApp).repo
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun search(query: String) {
        job?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _state.value = SearchState()
            return
        }
        job = viewModelScope.launch {
            delay(400) // espera a que el usuario termine de escribir
            _state.value = SearchState(loading = true, query = q)

            val targets = repo.addons.value
                .filter { it.manifest.hasResource("catalog") }
                .flatMap { addon -> addon.manifest.catalogs.filter { it.supportsSearch }.map { addon to it } }

            val results = coroutineScope {
                targets.map { (addon, catalog) ->
                    async { runCatching { repo.fetchCatalog(addon, catalog, q) }.getOrDefault(emptyList()) }
                }.awaitAll()
            }.flatten()

            ensureActive()
            val sections = results
                .distinctBy { it.type to it.id }
                .groupBy { it.type }
                .map { (type, items) -> SearchSection(type, items) }
                .sortedBy { section ->
                    when (section.type) {
                        "movie" -> 0
                        "series" -> 1
                        else -> 2
                    }
                }
            _state.value = SearchState(loading = false, query = q, sections = sections, searched = true)
        }
    }
}

@Composable
fun SearchScreen(onOpen: (MetaItem) -> Unit, vm: SearchViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                vm.search(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            singleLine = true,
            placeholder = { Text("Buscar películas y series") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.searched && state.sections.isEmpty() -> Text(
                    text = "Sin resultados para “${state.query}”",
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    state.sections.forEach { section ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = typeLabel(section.type),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(section.items) { result ->
                            PosterCard(result) { onOpen(result) }
                        }
                    }
                }
            }
        }
    }
}
