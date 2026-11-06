package com.sebasgl1.streamiocast.data

import kotlinx.serialization.Serializable
import kotlin.math.min

@Serializable
data class StreamHints(
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
)

/** Subtítulo según el protocolo de addons (respuesta de /subtitles o dentro de un stream). */
@Serializable
data class SubtitleItem(
    val id: String = "",
    val url: String = "",
    val lang: String = "",
    val label: String? = null,
)

@Serializable
data class SubtitlesResponse(val subtitles: List<SubtitleItem> = emptyList())

/** Un stream según el protocolo de addons: torrent (infoHash) o enlace directo (url). */
@Serializable
data class Stream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
    val behaviorHints: StreamHints? = null,
    val sources: List<String> = emptyList(),
    val subtitles: List<SubtitleItem> = emptyList(),
) {
    val text: String
        get() = title ?: description ?: name ?: ""
}

@Serializable
data class StreamsResponse(val streams: List<Stream> = emptyList())

data class StreamResult(
    val addon: String,
    val stream: Stream,
    val info: StreamInfo,
    /** Compatibilidad con el Chromecast. */
    val cls: Classification,
    /** Compatibilidad con el televisor DLNA. */
    val tv: Classification = classifyTv(info),
)

/** A qué aparato se le está filtrando la lista de streams. */
enum class StreamFilter { ALL, CHROMECAST, TV }

fun StreamResult.verdictFor(filter: StreamFilter): Verdict = when (filter) {
    StreamFilter.CHROMECAST -> cls.verdict
    StreamFilter.TV -> tv.verdict
    StreamFilter.ALL -> if (cls.verdict.ordinal <= tv.verdict.ordinal) cls.verdict else tv.verdict
}

data class StreamsOutcome(
    val results: List<StreamResult>,
    /** Nombres de los addons que no respondieron. */
    val failures: List<String>,
    /** Cuántos addons se consultaron. */
    val queried: Int,
)

/**
 * Orden de la lista: primero los compatibles; dentro de cada grupo, los torrents con pocas fuentes
 * (menos de 5 seeders) pasan al final; luego mayor resolución (tope 1080p) y más seeders.
 */
val streamComparator: Comparator<StreamResult> = streamComparator(StreamFilter.CHROMECAST)

/** Para el TV se prefiere hasta 4K; para "Todos", lo que sirve en ambos aparatos va primero. */
fun streamComparator(filter: StreamFilter): Comparator<StreamResult> {
    val cap = if (filter == StreamFilter.TV) 2160 else 1080
    return compareBy<StreamResult> {
        when (filter) {
            StreamFilter.CHROMECAST -> it.cls.verdict.ordinal
            StreamFilter.TV -> it.tv.verdict.ordinal
            StreamFilter.ALL -> it.cls.verdict.ordinal + it.tv.verdict.ordinal
        }
    }
        .thenBy { it.stream.url == null && (it.info.seeders ?: 0) < 5 }
        .thenByDescending { min(it.info.resolution ?: 0, cap) }
        .thenByDescending { it.info.seeders ?: 0 }
}
