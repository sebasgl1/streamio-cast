package com.sebasgl1.streamiocast.player

import com.sebasgl1.streamiocast.data.StreamResult
import com.sebasgl1.streamiocast.data.SubtitleItem
import com.sebasgl1.streamiocast.history.WatchTarget
import com.sebasgl1.streamiocast.subtitles.ExternalSubtitle
import com.sebasgl1.streamiocast.torrent.TorrentStats

/** Lo que se va a reproducir: un torrent (infoHash) o un enlace directo (url). */
data class PlaybackRequest(
    val target: WatchTarget,
    val infoHash: String?,
    val fileIdx: Int?,
    val url: String?,
    val filename: String?,
    val trackers: List<String>,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val streamSubtitles: List<SubtitleItem> = emptyList(),
    /** Grupo del stream ("torrentio|1080p|WEB-DL"): para elegir el del siguiente episodio. */
    val bingeGroup: String? = null,
    /** Lo abrió el paso automático al siguiente episodio (no espera a que le des reproducir). */
    val autoNext: Boolean = false,
    /** El episodio anterior se estaba convirtiendo en el celular: este sigue igual, sin preguntar. */
    val autoConvert: Boolean = false,
) {
    val title: String
        get() = target.displayTitle

    /** Tipo e id del vídeo, para pedir subtítulos ("series", "tt0944947:1:1"). */
    val contentType: String
        get() = target.type

    val contentId: String
        get() = target.videoId
}

fun StreamResult.toPlayback(target: WatchTarget) = PlaybackRequest(
    target = target,
    infoHash = stream.infoHash,
    fileIdx = stream.fileIdx,
    url = stream.url,
    filename = stream.behaviorHints?.filename,
    trackers = stream.sources.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") },
    videoHash = stream.behaviorHints?.videoHash,
    videoSize = stream.behaviorHints?.videoSize,
    streamSubtitles = stream.subtitles,
    bingeGroup = stream.behaviorHints?.bingeGroup,
)

/** ¿Es el mismo stream (mismo torrent y archivo, o mismo enlace)? */
fun PlaybackRequest.sameStreamAs(other: PlaybackRequest): Boolean =
    target.videoId == other.target.videoId &&
        infoHash?.lowercase() == other.infoHash?.lowercase() &&
        fileIdx == other.fileIdx &&
        url == other.url

sealed interface PlayerPhase {
    data class Preparing(val message: String) : PlayerPhase
    data object Playing : PlayerPhase
    data class Failed(val message: String) : PlayerPhase
}

/** Una pista de audio o de subtítulos que viene dentro del archivo. */
data class TrackOption(
    val id: String,
    val label: String,
    /** Idioma normalizado a 2 letras. */
    val lang: String?,
    val selected: Boolean,
    val supported: Boolean,
)

data class PlayerUi(
    val title: String = "",
    val phase: PlayerPhase = PlayerPhase.Preparing("Preparando…"),
    val buffering: Boolean = true,
    val stats: TorrentStats? = null,
    val isTorrent: Boolean = false,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    val externalSubs: List<ExternalSubtitle> = emptyList(),
    val subsLoading: Boolean = false,
    /** Id del subtítulo externo activo, o null. */
    val externalSelected: String? = null,
    val subtitleOffsetMs: Long = 0,
    val subtitleSize: Int = 1,
    val subtitleError: String? = null,
    /** No nulo mientras hay un Chromecast conectado. */
    val cast: CastUi? = null,
    val castMessage: String? = null,
    /** El vídeo real no es compatible con el Chromecast (por qué), a la espera de "intentar igual". */
    val castBlocked: String? = null,
    /** Posición desde la que se retomó (para el aviso "Desde el inicio"). */
    val resumedFromMs: Long? = null,
    /** ¿El reproductor del celular está sonando? */
    val localPlaying: Boolean = false,
    /** "T1 E3 · Título" si hay siguiente episodio. */
    val nextUp: String? = null,
    /** Segundos para pasar solo al siguiente episodio. */
    val nextCountdown: Int? = null,
    val nextLoading: Boolean = false,
    /** No nulo mientras se espera el buffer antes de arrancar. */
    val startWait: StartWait? = null,
    /** Segundos de buffer que se esperan si el vídeo se queda sin descargado (null = automático). */
    val rebufferSec: Int? = null,
    /** La revisión del archivo real salió bien (texto para mostrar en verde). */
    val compatOk: String? = null,
    /** Ya se tocó "No tiene sonido" para este vídeo. */
    val noSoundReported: Boolean = false,
    /** Se puede arreglar convirtiendo en el celular: qué se haría y qué cuesta. */
    val convertOffer: ConvertOffer? = null,
    /** Texto de estado mientras se convierte ("Convirtiendo en el celular: audio DTS → AC3 · 2.1x"). */
    val converting: String? = null,
)

/** La propuesta de conversión, en palabras para la persona. */
data class ConvertOffer(val what: String, val cost: String)

/** Espera antes de reproducir: cuánto vídeo hay descargado por delante y cuánto falta. */
data class StartWait(
    val bufferedMs: Long,
    val targetMs: Long,
    /** Modo manual: no arranca solo, aunque se llene. */
    val manual: Boolean,
    val ready: Boolean,
    /** Segundos estimados para llenarse, si se puede calcular. */
    val etaSec: Int?,
)
