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
)

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
    /** Posición desde la que se retomó (para el aviso "Desde el inicio"). */
    val resumedFromMs: Long? = null,
)
