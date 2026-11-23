package com.sebasgl1.streamiocast.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sebasgl1.streamiocast.StremioApp
import com.sebasgl1.streamiocast.data.Addon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUi(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as StremioApp).repo

    val addons: StateFlow<List<Addon>> = repo.addons
    val account: StateFlow<String?> = repo.accountEmail

    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    fun login(email: String, password: String) =
        run("Sesión iniciada y addons importados") {
            repo.login(email, password)
            getApplication<StremioApp>().sync.pull()
        }

    fun refresh() = run("Addons sincronizados") { repo.refreshAddons() }

    fun addAddon(url: String, onAdded: () -> Unit) = run("Addon añadido") {
        repo.addAddon(url)
        onAdded()
    }

    fun logout() {
        repo.logout()
        _ui.value = SettingsUi(message = "Sesión cerrada")
    }

    fun remove(addon: Addon) = repo.removeAddon(addon)

    private fun run(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.value = SettingsUi(busy = true)
            try {
                block()
                _ui.value = SettingsUi(message = success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = SettingsUi(message = e.message ?: "Error desconocido", isError = true)
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val addons by vm.addons.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var addonUrl by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Cuenta de Stremio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (account == null) {
            item {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Correo") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
            }
            item {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Contraseña") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            item {
                Button(
                    onClick = { vm.login(email.trim(), password) },
                    enabled = !ui.busy && email.isNotBlank() && password.isNotEmpty(),
                ) { Text("Iniciar sesión") }
            }
            item {
                Text(
                    text = "Importa tus addons (Torrentio incluido). Si tu cuenta es de Facebook o Apple no " +
                        "podrás entrar aquí: añade tus addons pegando su URL más abajo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                Text("Sesión iniciada como $account", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::refresh, enabled = !ui.busy) { Text("Sincronizar addons") }
                    OutlinedButton(onClick = vm::logout, enabled = !ui.busy) { Text("Cerrar sesión") }
                }
            }
        }

        item {
            if (ui.busy) {
                CircularProgressIndicator()
            } else if (ui.message != null) {
                Text(
                    text = ui.message.orEmpty(),
                    color = if (ui.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            Text(
                text = "Addons instalados",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        items(addons) { addon ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(addon.manifest.name.ifBlank { addon.manifest.id }, fontWeight = FontWeight.SemiBold)
                        val details = listOfNotNull(
                            addon.manifest.version?.let { "v$it" },
                            addon.manifest.types.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        ).joinToString("  •  ")
                        if (details.isNotEmpty()) {
                            Text(
                                text = details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { vm.remove(addon) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Quitar addon")
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = addonUrl,
                onValueChange = { addonUrl = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = true,
                label = { Text("URL del addon (manifest.json)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        item {
            Button(
                // Se borra solo si se añadió: si falla, puedes corregir la URL sin volver a pegarla.
                onClick = { vm.addAddon(addonUrl.trim()) { addonUrl = "" } },
                enabled = !ui.busy && addonUrl.isNotBlank(),
            ) { Text("Añadir addon") }
        }
        item {
            Text(
                text = "Para Torrentio: configúralo en torrentio.strem.fun y pega aquí la URL del manifest " +
                    "que te da al final (también sirven los enlaces stremio://).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { DeviceMemory() }
        item { StartPrefs() }
        item { LanguagePrefs() }
    }
}

/** Lo que la app aprendió con "No tiene sonido" para el aparato elegido, con opción de olvidarlo. */
@Composable
private fun DeviceMemory() {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as StremioApp
    val device by app.selectedDevice.collectAsStateWithLifecycle()
    val current = device ?: return
    var muted by remember(current.key) { mutableStateOf(app.compat.muted(current.key)) }
    val profile = when (current) {
        is com.sebasgl1.streamiocast.cast.TargetDevice.Tv -> app.compat.tvProfile(current).label
        is com.sebasgl1.streamiocast.cast.TargetDevice.Chromecast -> app.compat.castProfile(current).label
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 16.dp)) {
        Text("Transmisión", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Conectado a ${current.name} · reconocido como $profile", style = MaterialTheme.typography.bodyMedium)
        if (muted.isEmpty()) {
            Text(
                text = "Si un stream se ve pero no se oye, toca \"No tiene sonido\" mientras transmites: " +
                    "la app recuerda ese audio y lo marca en rojo para este aparato.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Audios que suenan mudos aquí: ${muted.joinToString(", ") { it.label }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                app.compat.forget(current.key)
                muted = emptySet()
            }) { Text("Olvidar lo aprendido") }
        }
    }
}
