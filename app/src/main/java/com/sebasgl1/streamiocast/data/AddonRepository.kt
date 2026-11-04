package com.sebasgl1.streamiocast.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Guarda los addons del usuario y habla con ellos (protocolo de addons de Stremio)
 * y con la API de cuentas de Stremio para importarlos.
 */
class AddonRepository(context: Context) {

    private val prefs = context.getSharedPreferences("streamio", Context.MODE_PRIVATE)
    private val http = Http()

    private val _addons = MutableStateFlow(loadAddons())
    val addons: StateFlow<List<Addon>> = _addons

    /** Sesión de la cuenta de Stremio (null si no has iniciado sesión). */
    val authKey: String?
        get() = prefs.getString(KEY_AUTH, null)

    private val _accountEmail = MutableStateFlow(prefs.getString(KEY_EMAIL, null))
    val accountEmail: StateFlow<String?> = _accountEmail

    // --- Persistencia ---------------------------------------------------------------

    private fun loadAddons(): List<Addon> {
        val raw = prefs.getString(KEY_ADDONS, null) ?: return emptyList()
        return runCatching {
            AppJson.decodeFromString(ListSerializer(Addon.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun saveAddons(list: List<Addon>) {
        prefs.edit()
            .putString(KEY_ADDONS, AppJson.encodeToString(ListSerializer(Addon.serializer()), list))
            .apply()
        _addons.value = list
    }

    /** Si el usuario aún no tiene addons, instala Cinemeta para que haya catálogos desde el primer arranque. */
    suspend fun ensureDefaults() {
        if (_addons.value.isNotEmpty()) return
        addAddon(CINEMETA_URL)
    }

    // --- Cuenta de Stremio ----------------------------------------------------------

    suspend fun login(email: String, password: String) {
        val body = buildJsonObject {
            put("type", "Login")
            put("email", email)
            put("password", password)
            put("facebook", false)
        }.toString()
        val response = parseObject(http.postJson("$API/login", body))
        response.errorMessage()?.let { throw IOException(it) }
        val authKey = response["result"].asObject()?.get("authKey")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw IOException("Respuesta inesperada de Stremio")
        prefs.edit().putString(KEY_AUTH, authKey).putString(KEY_EMAIL, email).apply()
        _accountEmail.value = email
        pullAddons(authKey)
    }

    /** Vuelve a traer la colección de addons de la cuenta con la sesión guardada. */
    suspend fun refreshAddons() {
        val authKey = prefs.getString(KEY_AUTH, null) ?: throw IOException("No hay sesión iniciada")
        pullAddons(authKey)
    }

    private suspend fun pullAddons(authKey: String) {
        val body = buildJsonObject {
            put("type", "AddonCollectionGet")
            put("authKey", authKey)
            put("update", true)
        }.toString()
        val response = parseObject(http.postJson("$API/addonCollectionGet", body))
        response.errorMessage()?.let { throw IOException(it) }
        val array = response["result"].asObject()?.get("addons")?.jsonArray
            ?: throw IOException("Stremio no devolvió addons")
        val list = array.mapNotNull { element ->
            runCatching { AppJson.decodeFromJsonElement(Addon.serializer(), element) }.getOrNull()
        }
        if (list.isEmpty()) throw IOException("La cuenta no tiene addons compatibles")
        saveAddons(list)
    }

    fun logout() {
        prefs.edit().remove(KEY_AUTH).remove(KEY_EMAIL).apply()
        _accountEmail.value = null
        saveAddons(emptyList())
    }

    // --- Addons manuales ------------------------------------------------------------

    suspend fun addAddon(input: String) {
        var url = input.trim().replace("stremio://", "https://")
        if (url.isEmpty()) throw IOException("Pega la URL del addon")
        if (!url.endsWith("/manifest.json")) url = url.trimEnd('/') + "/manifest.json"
        val manifest = AppJson.decodeFromString<Manifest>(http.get(url))
        if (manifest.id.isEmpty() && manifest.name.isEmpty()) throw IOException("Eso no parece un manifest de addon")
        val addon = Addon(url, manifest)
        saveAddons(_addons.value.filterNot { it.transportUrl == url } + addon)
    }

    fun removeAddon(addon: Addon) {
        saveAddons(_addons.value.filterNot { it.transportUrl == addon.transportUrl })
    }

    // --- Protocolo de addons --------------------------------------------------------

    suspend fun fetchCatalog(addon: Addon, catalog: CatalogDef, search: String? = null): List<MetaItem> {
        val type = Uri.encode(catalog.type)
        val id = Uri.encode(catalog.id)
        val path = if (search != null) {
            "catalog/$type/$id/search=${Uri.encode(search)}.json"
        } else {
            "catalog/$type/$id.json"
        }
        return AppJson.decodeFromString<CatalogResponse>(http.get("${addon.baseUrl}/$path")).metas
            .map { if (it.type.isEmpty()) it.copy(type = catalog.type) else it }
    }

    /** Pide el meta al primer addon que lo ofrezca para ese tipo e id. */
    suspend fun fetchMeta(type: String, id: String): MetaItem? {
        val candidates = _addons.value.filter { it.manifest.supports("meta", type, id) }
        for (addon in candidates) {
            val url = "${addon.baseUrl}/meta/${Uri.encode(type)}/${Uri.encode(id, ":")}.json"
            val meta = runCatching {
                AppJson.decodeFromString<MetaResponse>(http.get(url)).meta
            }.getOrNull()
            if (meta != null) return meta.let { if (it.type.isEmpty()) it.copy(type = type) else it }
        }
        return null
    }

    /**
     * Pide los streams de [id] a todos los addons que los ofrezcan, los clasifica según tu Chromecast
     * y los devuelve ordenados. Un addon caído no tumba al resto.
     */
    suspend fun fetchStreams(type: String, id: String): StreamsOutcome = coroutineScope {
        val targets = _addons.value.filter { it.manifest.supports("stream", type, id) }

        val responses = targets.map { addon ->
            async {
                val url = "${addon.baseUrl}/stream/${Uri.encode(type)}/${Uri.encode(id, ":")}.json"
                val streams = runCatching {
                    AppJson.decodeFromString<StreamsResponse>(http.get(url)).streams
                }.getOrNull()
                addon to streams
            }
        }.awaitAll()

        val failures = responses
            .filter { it.second == null }
            .map { it.first.manifest.name.ifBlank { it.first.manifest.id } }

        val results = responses
            .flatMap { (addon, streams) ->
                val addonName = addon.manifest.name.ifBlank { addon.manifest.id }
                streams.orEmpty()
                    .filter { it.infoHash != null || it.url != null }
                    .map { stream ->
                        val info = analyzeStream(stream)
                        StreamResult(addonName, stream, info, classify(info))
                    }
            }
            .distinctBy { r ->
                r.stream.infoHash?.let { hash -> hash.lowercase() + ":" + r.stream.fileIdx } ?: r.stream.url
            }
            .sortedWith(streamComparator)

        StreamsOutcome(results, failures, targets.size)
    }

    // --- Utilidades -----------------------------------------------------------------

    private fun parseObject(text: String): JsonObject =
        AppJson.parseToJsonElement(text) as? JsonObject ?: throw IOException("Respuesta inesperada")

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.errorMessage(): String? {
        val error = this["error"] ?: return null
        if (error is JsonNull) return null
        if (error is JsonObject) {
            if (error.isEmpty()) return null
            return (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()
        }
        return (error as? JsonPrimitive)?.contentOrNull ?: error.toString()
    }

    companion object {
        private const val API = "https://api.strem.io/api"
        const val CINEMETA_URL = "https://v3-cinemeta.strem.io/manifest.json"
        private const val KEY_ADDONS = "addons"
        private const val KEY_AUTH = "authKey"
        private const val KEY_EMAIL = "email"
    }
}
