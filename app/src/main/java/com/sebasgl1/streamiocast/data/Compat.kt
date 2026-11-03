package com.sebasgl1.streamiocast.data

/*
 * Clasificador de compatibilidad con Chromecast de 2ª generación.
 *
 * Reglas (fuente: documentación de Google Cast, "Supported media"):
 *  - Vídeo: H.264 High Profile hasta nivel 4.1 → 1080p a 30 fps (o 720p a 60 fps), 8 bits.
 *  - No soporta HEVC/H.265, VP9, AV1, ni HDR / Dolby Vision.
 *  - Contenedores listados: MP4, WebM, MP2T... MKV no figura, así que se reempaqueta (al transmitir).
 *  - Audio en MP4: AAC o MP3. El resto (AC3, E-AC3, DTS, TrueHD...) se convertirá a AAC (al transmitir).
 *
 * Todo sale del texto del stream: el códec real solo se conoce al abrir el archivo, así que el
 * veredicto es una estimación y por eso existe el nivel "Dudoso".
 */

enum class VideoCodec(val label: String) {
    H264("H.264"),
    HEVC("HEVC"),
    AV1("AV1"),
    VP9("VP9"),
    MPEG4("Xvid/DivX"),
    VC1("VC-1"),
    MPEG2("MPEG-2"),
    MIXED("varios"),
    UNKNOWN("?"),
}

enum class AudioCodec(val label: String, val castOk: Boolean) {
    AAC("AAC", true),
    MP3("MP3", true),
    OPUS("Opus", false),
    FLAC("FLAC", false),
    AC3("AC3", false),
    EAC3("E-AC3", false),
    DTS("DTS", false),
    TRUEHD("TrueHD", false),
}

/** El orden importa: se usa para ordenar la lista (mejor primero). */
enum class Verdict(val label: String) {
    GOOD("Compatible"),
    MAYBE("Dudoso"),
    BAD("No compatible"),
}

data class StreamInfo(
    val releaseName: String,
    val resolution: Int?,
    val codec: VideoCodec,
    val audio: Set<AudioCodec>,
    val tenBit: Boolean,
    val hdr: Boolean,
    val container: String?,
    val lowQuality: Boolean,
    val seeders: Int?,
    val sizeText: String?,
    val sizeBytes: Long?,
    val provider: String?,
)

data class Classification(
    val verdict: Verdict,
    val reasons: List<String>,
    val needsRemux: Boolean,
    val needsAudioConvert: Boolean,
)

fun StreamInfo.resolutionLabel(): String? = when {
    resolution == null -> null
    resolution >= 2160 -> "4K"
    else -> "${resolution}p"
}

// --- Patrones (se aplican sobre texto en minúsculas) ----------------------------------

private val R_RES = Regex("""\b(2160|1440|1080|720|576|480)[pi]\b""")
private val R_UHD = Regex("""\b(4k|uhd)\b""")

private val R_H264 = Regex("""\b(x\.?264|h\.?264|avc)\b""")
private val R_HEVC = Regex("""\b(x\.?265|h\.?265|hevc)\b""")
private val R_AV1 = Regex("""\bav1\b""")
private val R_VP9 = Regex("""\bvp9\b""")
private val R_MPEG4 = Regex("""\b(xvid|divx)\b""")
private val R_VC1 = Regex("""\bvc-?1\b""")
private val R_MPEG2 = Regex("""\bmpeg-?2\b""")

private val R_TEN_BIT = Regex("""\b(10[\s._-]?bits?|hi10p?)\b""")
private val R_HDR = Regex("""\b(hdr10\+?|hdr|dv|dovi|dolby[\s._-]?vision)\b""")

private val R_AAC = Regex("""\baac""")
private val R_MP3 = Regex("""\bmp3\b""")
private val R_OPUS = Regex("""\bopus\b""")
private val R_FLAC = Regex("""\bflac\b""")
private val R_EAC3 = Regex("""\b(e-?ac-?3|ddp|dd\+|dolby[\s._-]?digital[\s._-]?plus)""")
private val R_AC3 = Regex("""(?<!e-)\b(ac-?3|dd(?=[\s._-]?\d)|dolby[\s._-]?digital(?![\s._-]?plus))""")
private val R_DTS = Regex("""\bdts""")
private val R_TRUEHD = Regex("""\btruehd""")
private val R_ATMOS = Regex("""\batmos\b""")

private val R_CONTAINER = Regex("""\b(mkv|mp4|avi)\b""")
private val FILE_EXTS = setOf("mkv", "mp4", "m4v", "avi", "wmv", "mov", "ts", "m2ts", "webm", "mpg")

private val R_LOW = Regex("""\b(cam|camrip|hdcam|telesync|hdts|hdtc|telecine|dvdscr|screener|workprint)\b""")

// Torrentio escribe: "👤 123 💾 2.1 GB ⚙️ Proveedor"
private val R_SEEDERS = Regex("""👤\s*(\d+)""")
private val R_SIZE = Regex("""💾\s*([\d.,]+)\s*([KMGT])i?B""", RegexOption.IGNORE_CASE)
private val R_PROVIDER = Regex("""⚙️?\s*([^\n]+)""")

private const val GIB = 1L shl 30

private fun toBytes(number: String, unit: String): Long? {
    val n = number.replace(',', '.').toDoubleOrNull() ?: return null
    val multiplier = when (unit.uppercase()) {
        "K" -> 1L shl 10
        "M" -> 1L shl 20
        "G" -> 1L shl 30
        "T" -> 1L shl 40
        else -> return null
    }
    return (n * multiplier).toLong()
}

// --- Análisis --------------------------------------------------------------------------

fun analyzeStream(s: Stream): StreamInfo {
    val text = s.text
    val lines = text.lines()
    val seederLine = lines.indexOfFirst { it.contains("👤") }
    val nameLines = (if (seederLine >= 0) lines.take(seederLine) else lines)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val hay = listOfNotNull(text, s.name, s.behaviorHints?.bingeGroup, s.behaviorHints?.filename)
        .joinToString(" ")
        .lowercase()

    val heights = R_RES.findAll(hay).map { it.groupValues[1].toInt() }.toMutableList()
    if (R_UHD.containsMatchIn(hay)) heights.add(2160)

    val codecs = buildSet<VideoCodec> {
        if (R_H264.containsMatchIn(hay)) add(VideoCodec.H264)
        if (R_HEVC.containsMatchIn(hay)) add(VideoCodec.HEVC)
        if (R_AV1.containsMatchIn(hay)) add(VideoCodec.AV1)
        if (R_VP9.containsMatchIn(hay)) add(VideoCodec.VP9)
        if (R_MPEG4.containsMatchIn(hay)) add(VideoCodec.MPEG4)
        if (R_VC1.containsMatchIn(hay)) add(VideoCodec.VC1)
        if (R_MPEG2.containsMatchIn(hay)) add(VideoCodec.MPEG2)
    }
    val codec = when {
        codecs.isEmpty() -> VideoCodec.UNKNOWN
        codecs.size == 1 -> codecs.first()
        else -> VideoCodec.MIXED
    }

    val audio = buildSet<AudioCodec> {
        if (R_AAC.containsMatchIn(hay)) add(AudioCodec.AAC)
        if (R_MP3.containsMatchIn(hay)) add(AudioCodec.MP3)
        if (R_OPUS.containsMatchIn(hay)) add(AudioCodec.OPUS)
        if (R_FLAC.containsMatchIn(hay)) add(AudioCodec.FLAC)
        if (R_EAC3.containsMatchIn(hay)) add(AudioCodec.EAC3)
        if (R_AC3.containsMatchIn(hay)) add(AudioCodec.AC3)
        if (R_DTS.containsMatchIn(hay)) add(AudioCodec.DTS)
        if (R_TRUEHD.containsMatchIn(hay)) add(AudioCodec.TRUEHD)
        if (isEmpty() && R_ATMOS.containsMatchIn(hay)) add(AudioCodec.TRUEHD)
    }

    val fileName = s.behaviorHints?.filename?.lowercase()
    val urlPath = s.url?.substringBefore('?')?.lowercase()
    val container = listOfNotNull(fileName, urlPath)
        .map { it.substringAfterLast('.', "") }
        .firstOrNull { it in FILE_EXTS }
        ?: R_CONTAINER.find(hay)?.groupValues?.get(1)

    val sizeMatch = R_SIZE.find(text)
    val sizeText = sizeMatch?.let { "${it.groupValues[1]} ${it.groupValues[2].uppercase()}B" }
    val sizeBytes = s.behaviorHints?.videoSize
        ?: sizeMatch?.let { toBytes(it.groupValues[1], it.groupValues[2]) }

    return StreamInfo(
        releaseName = nameLines.joinToString(" / "),
        resolution = heights.maxOrNull(),
        codec = codec,
        audio = audio,
        tenBit = R_TEN_BIT.containsMatchIn(hay),
        hdr = R_HDR.containsMatchIn(hay),
        container = container,
        lowQuality = R_LOW.containsMatchIn(hay),
        seeders = R_SEEDERS.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        sizeText = sizeText,
        sizeBytes = sizeBytes,
        provider = R_PROVIDER.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
    )
}

// --- Veredicto -------------------------------------------------------------------------

fun classify(info: StreamInfo): Classification {
    val reasons = mutableListOf<String>()
    var bad = false
    var maybe = false

    val res = info.resolution
    when {
        res == null -> {
            maybe = true
            reasons += "No indica la resolución."
        }
        res > 1080 -> {
            bad = true
            val label = if (res >= 2160) "4K" else "${res}p"
            reasons += "$label: el Chromecast de 2ª gen llega solo a 1080p."
        }
    }

    when (info.codec) {
        VideoCodec.H264 -> Unit
        VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.VP9 -> {
            bad = true
            reasons += "Códec ${info.codec.label}: tu Chromecast solo reproduce H.264."
        }
        VideoCodec.MPEG4, VideoCodec.VC1, VideoCodec.MPEG2 -> {
            bad = true
            reasons += "Códec antiguo (${info.codec.label}): no es compatible."
        }
        VideoCodec.MIXED -> {
            maybe = true
            reasons += "El nombre menciona varios códecs (por ejemplo x264 y x265): puede ser el equivocado."
        }
        VideoCodec.UNKNOWN -> {
            maybe = true
            reasons += "El nombre no indica el códec; podría ser HEVC."
        }
    }

    val codecMayBeH264 = info.codec == VideoCodec.H264 ||
        info.codec == VideoCodec.UNKNOWN ||
        info.codec == VideoCodec.MIXED
    if (info.tenBit && codecMayBeH264) {
        bad = true
        reasons += "Vídeo de 10 bits: tu Chromecast solo decodifica H.264 de 8 bits."
    }
    if (info.hdr) {
        bad = true
        reasons += "HDR / Dolby Vision: no es compatible con tu Chromecast."
    }
    if (info.lowQuality) {
        bad = true
        reasons += "Grabación de cine (CAM/TS): mala calidad."
    }
    val size = info.sizeBytes
    if (size != null && size > 20 * GIB) {
        maybe = true
        reasons += "Archivo muy pesado (${info.sizeText ?: "más de 20 GB"}): puede cortarse al transmitir."
    }

    val audioOk = info.audio.any { it.castOk }
    val needsAudioConvert = info.audio.isNotEmpty() && !audioOk
    if (needsAudioConvert) {
        reasons += "Audio ${info.audio.joinToString("/") { it.label }}: habrá que convertirlo a AAC (al transmitir)."
    } else if (info.audio.isEmpty()) {
        reasons += "No indica el audio."
    }

    val needsRemux = info.container !in setOf("mp4", "m4v", "webm")
    if (needsRemux) {
        reasons += "Contenedor ${info.container?.uppercase() ?: "MKV u otro"}: se reempaquetará a MP4 (al transmitir)."
    }

    val verdict = when {
        bad -> Verdict.BAD
        maybe -> Verdict.MAYBE
        else -> Verdict.GOOD
    }
    if (verdict == Verdict.GOOD) {
        reasons.add(0, "H.264 de 8 bits a ${res}p: compatible con tu Chromecast.")
    }

    return Classification(verdict, reasons, needsRemux, needsAudioConvert)
}
