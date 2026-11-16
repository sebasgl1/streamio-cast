package com.sebasgl1.streamiocast.transcode

import com.sebasgl1.streamiocast.data.AudioCodec
import com.sebasgl1.streamiocast.data.CastProfile
import com.sebasgl1.streamiocast.data.Support
import com.sebasgl1.streamiocast.data.TvProfile
import com.sebasgl1.streamiocast.data.audioCodecOfMime
import com.sebasgl1.streamiocast.data.playableAudio
import com.sebasgl1.streamiocast.player.VideoFacts
import java.util.Locale

/*
 * Conversión en el celular: cuando el televisor o el Chromecast no reproducen el audio (o el vídeo)
 * de un archivo, FFmpeg lo convierte mientras lo ves y el aparato recibe la versión convertida.
 * Aquí se decide QUÉ convertir y se arma la orden de FFmpeg; no hay nada de Android, así que se
 * prueba con tests normales.
 */

enum class AudioTarget(val label: String) {
    /** La pista elegida ya sirve: se manda tal cual. */
    COPY("sin convertir"),
    AC3("AC3 5.1"),
    AAC("AAC estéreo"),
}

enum class VideoTarget { COPY, H264 }

enum class LiveContainer(val ffmpegFormat: String, val extension: String, val castMime: String, val dlnaMime: String) {
    /** Lo que mejor aceptan los televisores para vídeo que se va generando. */
    MPEGTS("mpegts", "ts", "video/mp2t", "video/mpeg"),

    /**
     * MP4 fragmentado, lo más estándar para el Chromecast. Con "delay_moov" la cabecera se escribe
     * al llegar el primer trozo de vídeo: el codificador por hardware entrega sus datos de
     * configuración dentro del propio vídeo y así quedan bien puestos.
     */
    FMP4("mp4", "mp4", "video/mp4", "video/mp4"),
}

/** La pista de audio que se va a mandar: su posición entre las pistas de audio del archivo. */
data class AudioPick(
    val index: Int,
    val mime: String?,
    /** "Español", "Inglés"… si el archivo lo dice. */
    val language: String?,
    /** ¿Es la misma pista que el aparato usaría por su cuenta (la primera o la "por defecto")? */
    val isDeviceDefault: Boolean,
)

data class ConvertPlan(
    val audio: AudioTarget,
    val audioIndex: Int,
    val audioFrom: String,
    val audioLanguage: String?,
    val audioIsOtherTrack: Boolean,
    val video: VideoTarget,
    val videoFrom: String,
    /** Alto de salida si se reduce (por ejemplo 1080), o null para dejarlo igual. */
    val scaleToHeight: Int?,
    val container: LiveContainer,
    /** Codificar el vídeo con el procesador (libx264) en vez del chip: respaldo, más lento. */
    val softwareVideo: Boolean = false,
) {
    /** Lo que la app le dice a la persona antes de hacerlo. */
    fun describe(device: String): String = buildList {
        val track = audioLanguage?.let { " en $it" }.orEmpty()
        when {
            audio == AudioTarget.COPY && audioIsOtherTrack ->
                add("Mando a $device la pista de audio$track que sí reproduce ($audioFrom), en vez de la primera del archivo. El audio no se convierte.")
            audio == AudioTarget.COPY -> add("El audio ($audioFrom) va tal cual.")
            else -> add("Convierto el audio$track de $audioFrom a ${audio.label} en el celular mientras lo ves.")
        }
        when (video) {
            VideoTarget.COPY -> add("El vídeo va tal cual, sin perder calidad.")
            VideoTarget.H264 -> add(
                "Convierto el vídeo de $videoFrom a H.264" +
                    (scaleToHeight?.let { " ${it}p" } ?: "") +
                    " en el celular: baja la calidad, puede ir a saltos y el HDR se ve con colores lavados.",
            )
        }
    }.joinToString(" ")

    /** Lo que cuesta, para que la persona decida. */
    fun cost(): String = when (video) {
        VideoTarget.COPY ->
            "Gasta algo más de batería y ocupa en el celular un espacio parecido al del vídeo mientras lo ves. " +
                "Al adelantar hay que esperar unos segundos."
        VideoTarget.H264 ->
            "Gasta bastante batería y calienta el celular. Ocupa espacio extra mientras lo ves y, al adelantar, " +
                "hay que esperar unos segundos."
    }

    /** Lo que se muestra mientras está convirtiendo. */
    fun statusLabel(): String = listOfNotNull(
        when {
            audio == AudioTarget.COPY && audioIsOtherTrack -> "otra pista de audio ($audioFrom)"
            audio == AudioTarget.COPY -> null
            else -> "audio $audioFrom → ${audio.label}"
        },
        if (video == VideoTarget.H264) {
            "vídeo $videoFrom → H.264" + (scaleToHeight?.let { " ${it}p" } ?: "") + if (softwareVideo) " (procesador)" else ""
        } else {
            null
        },
    ).joinToString(" y ").ifEmpty { "sin cambios" }

    /** La orden de FFmpeg. [startMs]: desde dónde empezar (al adelantar se vuelve a lanzar). */
    fun ffmpegArgs(input: String, output: String, startMs: Long): List<String> = buildList {
        addAll(listOf("-hide_banner", "-nostdin", "-y", "-loglevel", "warning", "-stats"))
        // Tolera fallos pequeños del archivo y genera las marcas de tiempo que falten.
        addAll(listOf("-fflags", "+genpts+discardcorrupt"))
        if (startMs > 0) addAll(listOf("-ss", String.format(Locale.US, "%.3f", startMs / 1000.0)))
        addAll(listOf("-i", input))
        addAll(listOf("-map", "0:v:0", "-map", "0:a:$audioIndex", "-sn", "-dn"))

        when (video) {
            VideoTarget.COPY -> addAll(listOf("-c:v", "copy"))
            VideoTarget.H264 -> {
                val scale = scaleToHeight?.let { "scale=-2:$it:flags=fast_bilinear" }
                if (softwareVideo) {
                    // Respaldo: codificador por software, en su modo más rápido.
                    addAll(listOf("-vf", listOfNotNull(scale, "format=yuv420p").joinToString(",")))
                    addAll(listOf("-c:v", "libx264", "-preset", "ultrafast", "-crf", "23"))
                    addAll(listOf("-maxrate", "8M", "-bufsize", "16M", "-g", "48"))
                } else {
                    // Codificador por hardware del celular (MediaCodec).
                    addAll(listOf("-vf", listOfNotNull(scale, "format=nv12").joinToString(",")))
                    addAll(listOf("-c:v", "h264_mediacodec", "-b:v", "8M", "-g", "48"))
                }
            }
        }
        when (audio) {
            AudioTarget.COPY -> addAll(listOf("-c:a", "copy"))
            AudioTarget.AC3 -> addAll(listOf("-c:a", "ac3", "-b:a", "640k", "-ac", "6"))
            AudioTarget.AAC -> addAll(listOf("-c:a", "aac", "-b:a", "192k", "-ac", "2"))
        }
        addAll(listOf("-max_muxing_queue_size", "4096"))
        if (container == LiveContainer.FMP4) {
            addAll(listOf("-movflags", "+frag_keyframe+delay_moov+default_base_moof"))
        }
        addAll(listOf("-f", container.ffmpegFormat, output))
    }
}

private fun audioLabel(mime: String?): String = audioCodecOfMime(mime)?.label
    ?: mime?.substringAfter('/')?.uppercase(Locale.US)
    ?: "desconocido"

private fun videoLabel(video: VideoFacts?): String {
    val codec = when (video?.mime) {
        "video/avc" -> "H.264"
        "video/hevc", "video/dolby-vision" -> "HEVC"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "Xvid/DivX"
        "video/mpeg2" -> "MPEG-2"
        "video/wvc1" -> "VC-1"
        else -> "vídeo"
    }
    val res = video?.height?.takeIf { it > 0 }?.let { if (it > 1100) " 4K" else " ${it}p" }.orEmpty()
    return codec + res
}

/** Perfiles H.264 de 10 bits o más: profile_idc 110, 122, 244. */
private fun isHighBitH264(video: VideoFacts): Boolean {
    val profile = video.codecs
        ?.takeIf { it.startsWith("avc1.", ignoreCase = true) && it.length >= 7 }
        ?.substring(5, 7)
        ?.toIntOrNull(16)
    return profile in setOf(0x6E, 0x7A, 0xF4)
}

/**
 * La pista de audio a mandar: la que la persona tiene elegida en la app (su idioma). Si no hay
 * ninguna elegida, la que el aparato usaría por su cuenta. Nunca se cambia de idioma a escondidas.
 */
private fun choosePick(tracks: List<AudioPick>, preferred: Int?): AudioPick? =
    tracks.firstOrNull { it.index == preferred }
        ?: tracks.firstOrNull { it.isDeviceDefault }
        ?: tracks.firstOrNull()

/**
 * Plan para un televisor DLNA. El vídeo casi nunca se toca (los TV decodifican casi todo); solo
 * el AV1 en un TV que no lo lee se pasa a H.264. Devuelve null si no hay nada que convertir.
 */
fun planForTv(
    video: VideoFacts?,
    tracks: List<AudioPick>,
    preferredAudio: Int?,
    profile: TvProfile,
): ConvertPlan? {
    val needsVideo = video?.mime == "video/av01" && profile.av1 == Support.NO
    val playable = profile.playableAudio()
    val pick = choosePick(tracks, preferredAudio)
    val codec = audioCodecOfMime(pick?.mime)
    val target = when {
        pick == null -> AudioTarget.COPY
        codec != null && codec in playable -> AudioTarget.COPY
        AudioCodec.AC3 in playable -> AudioTarget.AC3
        else -> AudioTarget.AAC
    }
    // Nada que hacer: la pista que el TV usa por su cuenta ya le sirve y el vídeo también.
    val audioChanges = pick != null && (target != AudioTarget.COPY || !pick.isDeviceDefault)
    if (!needsVideo && !audioChanges) return null
    return ConvertPlan(
        audio = target,
        audioIndex = pick?.index ?: 0,
        audioFrom = audioLabel(pick?.mime),
        audioLanguage = pick?.language,
        audioIsOtherTrack = pick != null && !pick.isDeviceDefault,
        video = if (needsVideo) VideoTarget.H264 else VideoTarget.COPY,
        videoFrom = videoLabel(video),
        scaleToHeight = if (needsVideo && video != null && video.height > 1080) 1080 else null,
        container = LiveContainer.MPEGTS,
    )
}

/**
 * Plan para un Chromecast. El de 2ª gen solo lee H.264 de 8 bits hasta 1080p con audio AAC/MP3:
 * lo demás se convierte. Devuelve null si no hay nada que convertir.
 */
fun planForCast(
    video: VideoFacts?,
    tracks: List<AudioPick>,
    preferredAudio: Int?,
    profile: CastProfile,
): ConvertPlan? {
    val maxHeight = if (profile.advanced) 2176 else 1088
    val maxWidth = if (profile.advanced) 3840 else 1920
    val allowed = buildSet {
        add("video/avc")
        if (profile.advanced) addAll(listOf("video/hevc", "video/x-vnd.on2.vp9", "video/dolby-vision"))
        if (profile.av1) add("video/av01")
    }
    val needsVideo = video != null && (
        (video.mime != null && video.mime !in allowed) ||
            video.width > maxWidth || video.height > maxHeight ||
            (!profile.advanced && isHighBitH264(video))
        )

    val playable = profile.playableAudio()
    val pick = choosePick(tracks, preferredAudio)
    val codec = audioCodecOfMime(pick?.mime)
    val audioTarget = when {
        pick == null -> AudioTarget.COPY
        codec != null && codec in playable -> AudioTarget.COPY
        else -> AudioTarget.AAC
    }
    val audioChanges = pick != null && (audioTarget != AudioTarget.COPY || !pick.isDeviceDefault)
    if (!needsVideo && !audioChanges) return null

    val limit = if (profile.advanced) 2160 else 1080
    return ConvertPlan(
        audio = audioTarget,
        audioIndex = pick?.index ?: 0,
        audioFrom = audioLabel(pick?.mime),
        audioLanguage = pick?.language,
        audioIsOtherTrack = pick != null && !pick.isDeviceDefault,
        video = if (needsVideo) VideoTarget.H264 else VideoTarget.COPY,
        videoFrom = videoLabel(video),
        scaleToHeight = if (needsVideo && video != null && video.height > limit) limit else null,
        container = LiveContainer.FMP4,
    )
}
