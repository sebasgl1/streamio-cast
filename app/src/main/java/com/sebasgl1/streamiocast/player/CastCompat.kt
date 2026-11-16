package com.sebasgl1.streamiocast.player

import com.sebasgl1.streamiocast.data.AudioCodec
import com.sebasgl1.streamiocast.data.CastProfile
import com.sebasgl1.streamiocast.data.Support
import com.sebasgl1.streamiocast.data.TvProfile
import com.sebasgl1.streamiocast.data.audioCodecOfMime
import com.sebasgl1.streamiocast.data.playableAudio
import com.sebasgl1.streamiocast.data.silentAudio
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

/** Perfiles H.264 de 10 bits o más (High 10, High 4:2:2, High 4:4:4): profile_idc 110, 122, 244. */
private val HIGH_BIT_PROFILES = setOf(0x6E, 0x7A, 0xF4)

private fun isHighBitH264(video: VideoFacts): Boolean {
    val profile = video.codecs
        ?.takeIf { it.startsWith("avc1.", ignoreCase = true) && it.length >= 7 }
        ?.substring(5, 7)
        ?.toIntOrNull(16)
    return profile != null && profile in HIGH_BIT_PROFILES
}

private fun isDolbyVision(video: VideoFacts): Boolean =
    video.mime == "video/dolby-vision" ||
        video.codecs?.let { it.startsWith("dvh", true) || it.startsWith("dva", true) } == true

/** Revisa el audio real (la pista que va a sonar) contra lo que el aparato reproduce. */
private fun audioIssue(audioMime: String?, playable: Set<AudioCodec>, silent: Set<AudioCodec>, device: String): Pair<Boolean, String>? {
    if (audioMime == null) return null
    val codec = audioCodecOfMime(audioMime)
    val name = audioName(audioMime)
    return when {
        codec != null && codec in silent -> true to "El audio es $name: en $device suena mudo."
        codec != null && codec in playable -> null
        else -> false to "El audio es $name: no es seguro que $device lo reproduzca; puede sonar mudo."
    }
}

/**
 * Revisa el vídeo real contra el Chromecast. [audioMime] es la pista de audio que va a sonar.
 * El de 2ª gen solo hace H.264 de 8 bits hasta 1080p a 30 fps; los "avanzados" (Ultra, Google TV)
 * también HEVC, 10 bits y 4K.
 */
fun checkCastCompat(video: VideoFacts?, audioMime: String?, profile: CastProfile = CastProfile.GEN2): CompatReport {
    val blocking = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val device = if (profile.advanced) "tu ${profile.label}" else "tu Chromecast"
    if (video != null) {
        val allowed = buildSet {
            add("video/avc")
            if (profile.advanced) addAll(listOf("video/hevc", "video/x-vnd.on2.vp9", "video/dolby-vision"))
            if (profile.av1) add("video/av01")
        }
        if (video.mime != null && video.mime !in allowed) {
            blocking += "El vídeo es ${videoName(video.mime)}: $device no lo reproduce."
        }
        val maxW = if (profile.advanced) 3840 else 1920
        val maxH = if (profile.advanced) 2176 else 1088
        if (video.width > maxW || video.height > maxH) {
            blocking += "Resolución ${video.width}×${video.height}: $device llega a ${if (profile.advanced) "4K" else "1080p"}."
        }
        if (!profile.advanced && isHighBitH264(video)) {
            blocking += "Vídeo de 10 bits: $device solo decodifica H.264 de 8 bits."
        }
        if (!profile.advanced && video.frameRate > 31f && video.height > 720) {
            warnings += "1080p a ${video.frameRate.toInt()} fps: $device puede ir a saltos (llega a 30 fps en 1080p)."
        }
    }
    audioIssue(audioMime, profile.playableAudio(), profile.silentAudio(), device)?.let { (silent, text) ->
        if (silent) blocking += text else warnings += text
    }
    return CompatReport(blocking, warnings)
}

/** Revisa el archivo real contra el TV DLNA: sobre todo el audio, que es lo que más falla. */
fun checkTvCompat(video: VideoFacts?, audioMime: String?, profile: TvProfile = TvProfile.GENERIC): CompatReport {
    val blocking = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val device = if (profile.label == "TV") "tu TV" else "tu ${profile.label}"
    if (video != null) {
        if (video.mime == "video/av01") {
            when (profile.av1) {
                Support.NO -> blocking += "El vídeo es AV1: $device no lo decodifica."
                Support.UNKNOWN -> warnings += "El vídeo es AV1: solo lo reproducen los TV de 2020 en adelante."
                Support.YES -> Unit
            }
        }
        if (isDolbyVision(video) && profile.dolbyVision != Support.YES) {
            warnings += "El vídeo es Dolby Vision: si $device no lo soporta, los colores salen verdes o morados."
        }
    }
    audioIssue(audioMime, profile.playableAudio(), profile.silentAudio(), device)?.let { (silent, text) ->
        if (silent) blocking += text else warnings += text
    }
    return CompatReport(blocking, warnings)
}
