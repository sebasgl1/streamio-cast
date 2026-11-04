package com.sebasgl1.streamiocast.player

import java.util.Locale

/** Lo que importa del vídeo real (leído del archivo, no del nombre). */
data class VideoFacts(
    val mime: String?,
    val width: Int,
    val height: Int,
    /** Cadena de códec, p. ej. "avc1.640028" (perfil High) o "avc1.6E0028" (High 10). */
    val codecs: String?,
    val frameRate: Float,
)

data class CompatReport(
    /** Impiden reproducir en el Chromecast. */
    val blocking: List<String>,
    /** Puede funcionar mal (sin sonido, a saltos). */
    val warnings: List<String>,
)

private fun videoName(mime: String?): String = when (mime) {
    "video/hevc" -> "HEVC (H.265)"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/av01" -> "AV1"
    "video/mp4v-es" -> "MPEG-4 (Xvid/DivX)"
    "video/mpeg2" -> "MPEG-2"
    "video/wvc1" -> "VC-1"
    else -> mime ?: "desconocido"
}

private fun audioName(mime: String): String = when (mime) {
    "audio/ac3" -> "AC3"
    "audio/eac3", "audio/eac3-joc" -> "E-AC3"
    "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd" -> "DTS"
    "audio/true-hd" -> "TrueHD"
    "audio/flac" -> "FLAC"
    else -> mime.substringAfter('/').uppercase(Locale.US)
}

private val CAST_AUDIO = setOf("audio/mp4a-latm", "audio/mpeg", "audio/opus", "audio/vorbis")

/** Perfiles H.264 de 10 bits o más (High 10, High 4:2:2, High 4:4:4): profile_idc 110, 122, 244. */
private val HIGH_BIT_PROFILES = setOf(0x6E, 0x7A, 0xF4)

/**
 * Revisa el vídeo real contra el Chromecast de 2ª gen (H.264 8 bits hasta 1080p a 30 fps;
 * audio AAC/MP3). [audioMime] es la primera pista de audio, que es la que suena en el Chromecast.
 */
fun checkCastCompat(video: VideoFacts?, audioMime: String?): CompatReport {
    val blocking = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    if (video != null) {
        if (video.mime != null && video.mime != "video/avc") {
            blocking += "El vídeo es ${videoName(video.mime)}: tu Chromecast solo reproduce H.264."
        }
        if (video.width > 1920 || video.height > 1088) {
            blocking += "Resolución ${video.width}×${video.height}: tu Chromecast llega a 1080p."
        }
        val profile = video.codecs
            ?.takeIf { it.startsWith("avc1.", ignoreCase = true) && it.length >= 7 }
            ?.substring(5, 7)
            ?.toIntOrNull(16)
        if (profile != null && profile in HIGH_BIT_PROFILES) {
            blocking += "Vídeo de 10 bits: tu Chromecast solo decodifica H.264 de 8 bits."
        }
        if (video.frameRate > 31f && video.height > 720) {
            warnings += "1080p a ${video.frameRate.toInt()} fps: tu Chromecast puede ir a saltos (llega a 30 fps en 1080p)."
        }
    }
    if (audioMime != null && audioMime !in CAST_AUDIO) {
        warnings += "El audio es ${audioName(audioMime)}: en tu Chromecast puede sonar mudo."
    }
    return CompatReport(blocking, warnings)
}
