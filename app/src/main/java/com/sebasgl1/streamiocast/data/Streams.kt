package com.sebasgl1.streamiocast.data

import kotlinx.serialization.Serializable
import kotlin.math.min

@Serializable
data class StreamHints(
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
)

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
    val cls: Classification,
)

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
val streamComparator: Comparator<StreamResult> =
    compareBy<StreamResult> { it.cls.verdict.ordinal }
        .thenBy { it.stream.url == null && (it.info.seeders ?: 0) < 5 }
        .thenByDescending { min(it.info.resolution ?: 0, 1080) }
        .thenByDescending { it.info.seeders ?: 0 }
