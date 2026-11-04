package com.sebasgl1.streamiocast.data

import com.sebasgl1.streamiocast.history.WatchEntry
import com.sebasgl1.streamiocast.history.WatchHistory
import com.sebasgl1.streamiocast.history.WatchTarget
import com.sebasgl1.streamiocast.history.seasonEpisodeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Instant

/**
 * Sincroniza "Continuar viendo" con tu cuenta de Stremio: al abrir la app trae el progreso de tu
 * biblioteca y, mientras ves algo, sube dónde vas (así lo ves igual en la app oficial).
 * Sin sesión iniciada no hace nada.
 */
class StremioSync(private val repo: AddonRepository, private val history: WatchHistory) {
    private val http = Http()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @Volatile
    var lastError: String? = null
        private set

    fun pull() {
        val key = repo.authKey ?: return
        scope.launch {
            runCatching {
                val items = datastoreGet(key, emptyList(), all = true)
                val remote = continueWatchingFrom(items).map { it.toEntry() }
                history.mergeRemote(remote)
                lastError = null
            }.onFailure { lastError = it.message }
        }
    }

    fun push(progress: ProgressInput) {
        val key = repo.authKey ?: return
        scope.launch {
            lock.withLock {
                runCatching {
                    val existing = datastoreGet(key, listOf(progress.metaId), all = false).firstOrNull()
                    datastorePut(key, applyProgress(existing, progress, Instant.now()))
                    lastError = null
                }.onFailure { lastError = it.message }
            }
        }
    }

    private fun RemoteProgress.toEntry(): WatchEntry {
        val se = seasonEpisodeOf(videoId)
        return WatchEntry(
            target = WatchTarget(
                videoId = videoId,
                metaId = metaId,
                type = type,
                name = name,
                poster = poster,
                season = se?.first,
                episode = se?.second,
            ),
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = updatedAt,
            // timeOffset = 1: Stremio ya pasó al siguiente episodio.
            upNext = positionMs < 30_000,
        )
    }

    private suspend fun datastoreGet(key: String, ids: List<String>, all: Boolean): List<JsonObject> {
        val body = buildJsonObject {
            put("authKey", key)
            put("collection", COLLECTION)
            put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
            put("all", all)
        }.toString()
        val response = AppJson.parseToJsonElement(http.postJson("$API/datastoreGet", body)) as? JsonObject
            ?: throw IOException("Respuesta inesperada de Stremio")
        errorOf(response)?.let { throw IOException(it) }
        return (response["result"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    private suspend fun datastorePut(key: String, item: JsonObject) {
        val body = buildJsonObject {
            put("authKey", key)
            put("collection", COLLECTION)
            put("changes", JsonArray(listOf(item)))
        }.toString()
        val response = AppJson.parseToJsonElement(http.postJson("$API/datastorePut", body)) as? JsonObject
            ?: throw IOException("Respuesta inesperada de Stremio")
        errorOf(response)?.let { throw IOException(it) }
    }

    private fun errorOf(response: JsonObject): String? {
        val error = response["error"] ?: return null
        if (error is JsonNull) return null
        if (error is JsonObject) {
            if (error.isEmpty()) return null
            return (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()
        }
        return (error as? JsonPrimitive)?.contentOrNull
    }

    companion object {
        private const val API = "https://api.strem.io/api"
        private const val COLLECTION = "libraryItem"
    }
}
