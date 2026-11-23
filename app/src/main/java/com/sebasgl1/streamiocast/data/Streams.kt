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

// --- Ordenar la lista ---------------------------------------------------------------------

/** Cómo ordenar la lista de streams. */
enum class StreamSort(val label: String, val defaultDescending: Boolean) {
    /** Compatibles primero, luego calidad y seeds (lo de siempre). */
    RECOMMENDED("Recomendado", true),
    SEEDS("Seeds", true),
    RESOLUTION("Resolución", true),
    SIZE("Peso", false),
}

/**
 * Filtra por aparato y ordena. Con un aparato elegido (Chromecast o TV) se quitan los que no le
 * sirven y, dentro de lo que queda, primero los compatibles seguros (verde) y después los dudosos
 * (amarillo), cada grupo ordenado por el criterio elegido. Con "Todos" el orden es solo el criterio.
 * Los que no traen el dato (sin seeds, sin resolución, sin peso) van siempre al final.
 */
fun arrangeStreams(
    results: List<StreamResult>,
    filter: StreamFilter,
    sort: StreamSort,
    descending: Boolean,
): List<StreamResult> {
    val shown = if (filter == StreamFilter.ALL) results else results.filter { it.verdictFor(filter) != Verdict.BAD }
    if (sort == StreamSort.RECOMMENDED) return shown.sortedWith(streamComparator(filter))

    val key: (StreamResult) -> Long? = when (sort) {
        StreamSort.SEEDS -> { r -> r.info.seeders?.toLong() }
        StreamSort.RESOLUTION -> { r -> r.info.resolution?.toLong() }
        StreamSort.SIZE -> { r -> r.info.sizeBytes }
        StreamSort.RECOMMENDED -> { _ -> null }
    }
    // Desempate: por seeds (o, si se ordena por seeds, por resolución), siempre de mayor a menor.
    val tieBreak: (StreamResult) -> Long = when (sort) {
        StreamSort.SEEDS -> { r -> r.info.resolution?.toLong() ?: -1 }
        else -> { r -> r.info.seeders?.toLong() ?: -1 }
    }
    val byKey = Comparator<StreamResult> { a, b ->
        val ka = key(a)
        val kb = key(b)
        when {
            ka == null && kb == null -> 0
            ka == null -> 1 // sin dato, al final
            kb == null -> -1
            descending -> kb.compareTo(ka)
            else -> ka.compareTo(kb)
        }
    }.thenByDescending(tieBreak)

    val comparator = if (filter == StreamFilter.ALL) {
        byKey
    } else {
        compareBy<StreamResult> { it.verdictFor(filter).ordinal }.then(byKey)
    }
    return shown.sortedWith(comparator)
}

/** Identifica un stream (torrent + archivo, o enlace directo) para recordar cómo le fue al reproducirlo. */
fun streamKeyOf(infoHash: String?, fileIdx: Int?, url: String?): String? =
    infoHash?.let { "${it.lowercase()}:${fileIdx ?: -1}" } ?: url

val StreamResult.key: String?
    get() = streamKeyOf(stream.infoHash, stream.fileIdx, stream.url)

/**
 * Lo que dijo la revisión del archivo real en un aparato: pesa más que lo que se adivina por el
 * nombre. [problem] null = compatible.
 */
data class Verified(val problem: String?)

fun Classification.withVerified(v: Verified?): Classification = when {
    v == null -> this
    v.problem != null -> copy(verdict = Verdict.BAD, reasons = listOf("Revisado al reproducirlo: ${v.problem}"))
    else -> copy(
        verdict = Verdict.GOOD,
        reasons = listOf("✓ Revisado al reproducirlo: vídeo y audio compatibles.") +
            reasons.filterNot { it.startsWith("Revisado") },
    )
}
