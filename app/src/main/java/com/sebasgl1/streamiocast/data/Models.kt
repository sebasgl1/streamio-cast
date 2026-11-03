package com.sebasgl1.streamiocast.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Json tolerante: los addons de la comunidad no siempre respetan el protocolo al pie de la letra. */
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class ExtraDef(
    val name: String = "",
    val isRequired: Boolean = false,
)

@Serializable
data class CatalogDef(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<ExtraDef> = emptyList(),
    val extraRequired: List<String> = emptyList(),
)

val CatalogDef.supportsSearch: Boolean
    get() = extra.any { it.name == "search" }

/** Catálogos que exigen un parámetro (género, búsqueda...) no sirven para la pantalla de inicio. */
val CatalogDef.requiresExtra: Boolean
    get() = extra.any { it.isRequired } || extraRequired.isNotEmpty()

@Serializable
data class Manifest(
    val id: String = "",
    val name: String = "",
    val version: String? = null,
    val description: String? = null,
    val logo: String? = null,
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
    val catalogs: List<CatalogDef> = emptyList(),
)

fun Manifest.hasResource(resource: String): Boolean = resources.any { r ->
    when (r) {
        is JsonPrimitive -> r.content == resource
        is JsonObject -> (r["name"] as? JsonPrimitive)?.content == resource
        else -> false
    }
}

/**
 * ¿Este addon ofrece [resource] para el [type] (y opcionalmente el [id])?
 * Los recursos pueden ser un texto simple o un objeto con sus propios tipos y prefijos.
 */
fun Manifest.supports(resource: String, type: String, id: String? = null): Boolean {
    for (r in resources) {
        var rTypes: List<String>? = null
        var rPrefixes: List<String>? = null
        val name = when (r) {
            is JsonPrimitive -> r.content
            is JsonObject -> {
                rTypes = (r["types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                rPrefixes = (r["idPrefixes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                (r["name"] as? JsonPrimitive)?.content
            }
            else -> null
        } ?: continue
        if (name != resource) continue
        val allowedTypes = rTypes ?: types
        if (allowedTypes.isNotEmpty() && type !in allowedTypes) continue
        val prefixes = rPrefixes ?: idPrefixes
        if (id != null && !prefixes.isNullOrEmpty() && prefixes.none { id.startsWith(it) }) continue
        return true
    }
    return false
}

@Serializable
data class Addon(
    val transportUrl: String,
    val manifest: Manifest,
) {
    /** URL base del addon: la de su manifest sin el "/manifest.json" final. */
    val baseUrl: String
        get() = transportUrl.removeSuffix("/manifest.json").trimEnd('/')
}

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
) {
    val label: String
        get() = title ?: name ?: id
}

@Serializable
data class MetaItem(
    val id: String,
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val videos: List<Video> = emptyList(),
)

@Serializable
data class CatalogResponse(val metas: List<MetaItem> = emptyList())

@Serializable
data class MetaResponse(val meta: MetaItem? = null)

fun typeLabel(type: String): String = when (type) {
    "movie" -> "Películas"
    "series" -> "Series"
    "channel" -> "Canales"
    "tv" -> "TV"
    "anime" -> "Anime"
    else -> type.replaceFirstChar { it.uppercase() }
}
