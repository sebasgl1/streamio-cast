package com.sebasgl1.streamiocast.subtitles

import android.net.Uri
import com.sebasgl1.streamiocast.data.AddonRepository
import com.sebasgl1.streamiocast.data.AppJson
import com.sebasgl1.streamiocast.data.Http
import com.sebasgl1.streamiocast.data.SubtitleItem
import com.sebasgl1.streamiocast.data.SubtitlesResponse
import com.sebasgl1.streamiocast.data.supports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class ExternalSubtitle(
    val id: String,
    val url: String,
    /** Código tal como lo manda el addon ("spa", "pob"...). */
    val lang: String,
    val label: String,
    val source: String,
)

/** Subtítulos de los addons (como Stremio). Si no hay ninguno instalado, usa OpenSubtitles v3. */
class SubtitleRepository(private val addons: AddonRepository) {
    private val http = Http()

    suspend fun list(
        type: String,
        id: String,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        streamSubtitles: List<SubtitleItem>,
    ): List<ExternalSubtitle> = coroutineScope {
        val installed = addons.addons.value
            .filter { it.manifest.supports("subtitles", type, id) }
            .map { it.baseUrl to it.manifest.name.ifBlank { it.manifest.id } }
        val bases = installed.ifEmpty { listOf(OPENSUBTITLES to "OpenSubtitles") }

        val extra = buildList {
            videoHash?.let { add("videoHash=$it") }
            videoSize?.let { add("videoSize=$it") }
            filename?.let { add("filename=" + Uri.encode(it)) }
        }.joinToString("&")
        val path = "subtitles/${Uri.encode(type)}/${Uri.encode(id, ":")}" +
            (if (extra.isNotEmpty()) "/$extra" else "") + ".json"

        val fromAddons = bases.map { (base, name) ->
            async {
                runCatching {
                    AppJson.decodeFromString<SubtitlesResponse>(http.get("$base/$path")).subtitles.map { it to name }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()

        (streamSubtitles.map { it to "Stream" } + fromAddons)
            .filter { it.first.url.isNotBlank() }
            .distinctBy { it.first.url }
            .mapIndexed { index, (item, source) ->
                ExternalSubtitle(
                    id = item.id.ifBlank { "sub$index" } + "#$index",
                    url = item.url,
                    lang = item.lang,
                    label = item.label.orEmpty(),
                    source = source,
                )
            }
    }

    suspend fun load(sub: ExternalSubtitle): List<SubCue> = withContext(Dispatchers.Default) {
        parseSubtitles(decodeSubtitleBytes(http.getBytes(sub.url)))
    }

    /** Hash y tamaño de un vídeo remoto pidiendo solo su inicio y su final. */
    suspend fun remoteFingerprint(url: String): Pair<String?, Long?>? {
        val head = http.getRange(url, "0-${HASH_CHUNK - 1}") ?: return null
        val total = head.total ?: return null
        if (total < HASH_CHUNK || head.bytes.size < HASH_CHUNK) return null to total
        val tail = http.getRange(url, "${total - HASH_CHUNK}-${total - 1}") ?: return null to total
        if (tail.bytes.size < HASH_CHUNK) return null to total
        return openSubtitlesHash(total, head.bytes.copyOf(HASH_CHUNK), tail.bytes.copyOf(HASH_CHUNK)) to total
    }

    companion object {
        const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io"
    }
}
