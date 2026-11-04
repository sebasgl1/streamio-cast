package com.sebasgl1.streamiocast.history

import android.content.Context
import com.sebasgl1.streamiocast.data.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Qué se está viendo: la película, o el episodio dentro de su serie. */
@Serializable
data class WatchTarget(
    /** Id del vídeo: "tt0133093" o, en series, "tt0944947:1:2". */
    val videoId: String,
    /** Id de la película o serie (para abrir su ficha). */
    val metaId: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
) {
    val displayTitle: String
        get() = if (season != null && episode != null) "$name · T$season E$episode" else name
}

/** Subtítulo elegido: apagado, uno del archivo (por idioma) o uno externo (por URL e idioma). */
@Serializable
data class SavedSubtitle(
    val kind: String,
    val lang: String? = null,
    val url: String? = null,
    val offsetMs: Long = 0,
) {
    companion object {
        const val OFF = "off"
        const val EMBEDDED = "embedded"
        const val EXTERNAL = "external"
    }
}

@Serializable
data class WatchEntry(
    val target: WatchTarget,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watched: Boolean = false,
    val updatedAt: Long = 0,
    val subtitle: SavedSubtitle? = null,
    val audioLang: String? = null,
    /** Quitado a mano de "Continuar viendo" (vuelve a salir si lo sigues viendo). */
    val hidden: Boolean = false,
    /** Episodio siguiente a uno que terminaste: sale en "Continuar viendo" aunque no lo hayas empezado. */
    val upNext: Boolean = false,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Seguimiento de lo que ves: dónde vas, si ya lo viste y qué audio/subtítulos elegiste.
 * Se guarda en un JSON en el almacenamiento interno de la app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchHistory(context: Context) {
    private val file = File(context.filesDir, "seguimiento.json")
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val serializer = ListSerializer(WatchEntry.serializer())

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, WatchEntry>> = _entries

    fun get(videoId: String): WatchEntry? = _entries.value[videoId]

    /** Lo último que viste de esa serie o película (para heredar idioma de subtítulos y audio). */
    fun lastForMeta(metaId: String): WatchEntry? =
        _entries.value.values.filter { it.target.metaId == metaId }.maxByOrNull { it.updatedAt }

    @Synchronized
    fun update(target: WatchTarget, change: (WatchEntry) -> WatchEntry) {
        val current = _entries.value[target.videoId] ?: WatchEntry(target)
        val next = change(current).copy(target = target, updatedAt = System.currentTimeMillis())
        _entries.value = _entries.value + (target.videoId to next)
        persist()
    }

    /**
     * Mezcla lo traído de tu cuenta de Stremio: gana lo más reciente. Se conservan tus ajustes
     * locales de subtítulos y audio.
     */
    @Synchronized
    fun mergeRemote(remote: List<WatchEntry>) {
        var changed = false
        val map = _entries.value.toMutableMap()
        for (r in remote) {
            val local = map[r.target.videoId]
            if (local != null && local.updatedAt >= r.updatedAt) continue
            map[r.target.videoId] = r.copy(
                subtitle = local?.subtitle,
                audioLang = local?.audioLang,
                watched = local?.watched ?: false,
                target = if (local != null && r.target.poster == null) r.target.copy(poster = local.target.poster) else r.target,
            )
            changed = true
        }
        if (changed) {
            _entries.value = map
            persist()
        }
    }

    /** Quita una serie o película de "Continuar viendo". */
    @Synchronized
    fun hideMeta(metaId: String) {
        _entries.value = _entries.value.mapValues { (_, e) ->
            if (e.target.metaId == metaId) e.copy(hidden = true) else e
        }
        persist()
    }

    private fun load(): Map<String, WatchEntry> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        AppJson.decodeFromString(serializer, file.readText()).associateBy { it.target.videoId }
    }.getOrDefault(emptyMap())

    private fun persist() {
        val snapshot = _entries.value.values.toList()
        writer.launch {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(AppJson.encodeToString(serializer, snapshot))
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }
}
