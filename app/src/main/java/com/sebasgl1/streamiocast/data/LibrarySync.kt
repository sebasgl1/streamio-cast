package com.sebasgl1.streamiocast.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/*
 * Formato de la biblioteca de Stremio (stremio-core, types/library/library_item.rs):
 * un LibraryItem por película o serie, con el progreso en "state":
 *   timeOffset / duration / timeWatched en milisegundos, video_id = episodio actual.
 * "Continuar viendo" = type != "other", (no eliminado o temporal) y timeOffset > 0.
 * Lo que ves sin añadirlo a tu biblioteca se guarda como ítem "temporal" (removed + temp).
 */

/** Progreso de un vídeo para subir a Stremio. */
data class ProgressInput(
    val metaId: String,
    val type: String,
    val name: String,
    val poster: String?,
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    /** Si se terminó un episodio, el siguiente (Stremio pasa a él con timeOffset = 1). */
    val nextVideoId: String?,
    /** "Quitar visto": deja el vídeo como no visto y desde el principio. */
    val unwatch: Boolean = false,
)

/** Progreso leído de Stremio para "Continuar viendo". */
data class RemoteProgress(
    val metaId: String,
    val type: String,
    val name: String,
    val poster: String?,
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

fun parseInstantMs(text: String?): Long {
    if (text.isNullOrBlank()) return 0
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrElse {
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrDefault(0)
    }
}

/**
 * Aplica el progreso al LibraryItem (o crea uno temporal si no existe). Solo cambia los campos de
 * progreso: todo lo demás (incluidos campos que no conocemos, como los episodios vistos) se conserva.
 */
fun applyProgress(existing: JsonObject?, p: ProgressInput, now: Instant): JsonObject {
    val nowText = now.toString()
    val base: Map<String, JsonElement> = existing ?: mapOf(
        "_id" to JsonPrimitive(p.metaId),
        "name" to JsonPrimitive(p.name),
        "type" to JsonPrimitive(p.type),
        "poster" to (p.poster?.let { JsonPrimitive(it) } ?: JsonNull),
        "posterShape" to JsonPrimitive("poster"),
        "removed" to JsonPrimitive(true),
        "temp" to JsonPrimitive(true),
        "_ctime" to JsonPrimitive(nowText),
        "behaviorHints" to buildJsonObject {
            put("defaultVideoId", JsonNull)
            put("featuredVideoId", JsonNull)
            put("hasScheduledVideos", false)
        },
    )
    val old = existing?.get("state") as? JsonObject ?: JsonObject(emptyMap())
    val sameVideo = old.str("video_id") == p.videoId

    var timeWatched = if (sameVideo) old.long("timeWatched") else 0L
    var overall = old.long("overallTimeWatched")
    if (sameVideo) {
        val delta = (p.positionMs - old.long("timeOffset")).coerceAtLeast(0)
        timeWatched += delta
        overall += delta
    } else {
        overall += old.long("timeWatched")
    }
    var flagged = if (sameVideo) old.long("flaggedWatched") else 0L
    var timesWatched = old.long("timesWatched")
    var offset = p.positionMs
    var videoId = p.videoId
    val duration = if (p.durationMs > 0) p.durationMs else old.long("duration")

    if (p.unwatch) {
        flagged = 0
        offset = 0
    } else if (p.watched) {
        if (flagged == 0L) {
            flagged = 1
            timesWatched += 1
        }
        offset = 0
        if (p.nextVideoId != null) {
            // Como Stremio: pasa al siguiente episodio, que queda en "Continuar viendo".
            overall += timeWatched
            timeWatched = 0
            flagged = 0
            videoId = p.nextVideoId
            offset = 1
        }
    }

    val state = buildJsonObject {
        old.forEach { (k, v) -> put(k, v) }
        put("lastWatched", nowText)
        put("timeWatched", timeWatched)
        put("timeOffset", offset)
        put("overallTimeWatched", overall)
        put("timesWatched", timesWatched)
        put("flaggedWatched", flagged)
        put("duration", duration)
        put("video_id", videoId)
        if (!old.containsKey("watched")) put("watched", JsonNull)
        if (!old.containsKey("noNotif")) put("noNotif", false)
    }

    // Un ítem eliminado de la biblioteca solo aparece en "Continuar viendo" si es temporal.
    val removed = (base["removed"] as? JsonPrimitive)?.booleanOrNull ?: true
    return buildJsonObject {
        base.forEach { (k, v) -> put(k, v) }
        if (removed) put("temp", true)
        put("_mtime", nowText)
        put("state", state)
    }
}

/** Lo que Stremio muestra en "Continuar viendo". */
fun continueWatchingFrom(items: List<JsonObject>): List<RemoteProgress> = items.mapNotNull { item ->
    val type = item.str("type") ?: return@mapNotNull null
    if (type == "other") return@mapNotNull null
    if (item.bool("removed") && !item.bool("temp")) return@mapNotNull null
    val state = item["state"] as? JsonObject ?: return@mapNotNull null
    val offset = state.long("timeOffset")
    if (offset <= 0) return@mapNotNull null
    val id = item.str("_id") ?: return@mapNotNull null
    RemoteProgress(
        metaId = id,
        type = type,
        name = item.str("name") ?: id,
        poster = item.str("poster")?.takeIf { it.isNotBlank() },
        videoId = state.str("video_id")?.takeIf { it.isNotBlank() } ?: id,
        positionMs = offset,
        durationMs = state.long("duration"),
        updatedAt = parseInstantMs(item.str("_mtime")),
    )
}
