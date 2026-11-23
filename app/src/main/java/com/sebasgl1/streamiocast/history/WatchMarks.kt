package com.sebasgl1.streamiocast.history

import com.sebasgl1.streamiocast.StremioApp
import com.sebasgl1.streamiocast.data.MetaItem
import com.sebasgl1.streamiocast.data.ProgressInput
import com.sebasgl1.streamiocast.data.Video
import com.sebasgl1.streamiocast.player.nextEpisodeVideo

/** Lo que se guarda para una película o un episodio de esta ficha. */
fun watchTargetFor(meta: MetaItem, video: Video?): WatchTarget = if (video == null) {
    WatchTarget(
        videoId = meta.id,
        metaId = meta.id,
        type = meta.type.ifEmpty { "movie" },
        name = meta.name,
        poster = meta.poster,
    )
} else {
    WatchTarget(
        videoId = video.id,
        metaId = meta.id,
        type = meta.type.ifEmpty { "series" },
        name = meta.name,
        poster = meta.poster,
        season = video.season ?: 0,
        episode = video.episode ?: 0,
        episodeTitle = video.label,
    )
}

/**
 * Marca (o desmarca) como visto, por ejemplo porque lo viste en otro aparato. [videos] vacío = la
 * película. Con episodios: al marcar, el siguiente sin empezar queda en "Continuar viendo"; al
 * desmarcar, vuelven a empezar desde cero. También se sube a tu cuenta de Stremio.
 */
fun StremioApp.setWatched(meta: MetaItem, videos: List<Video>, watched: Boolean) {
    val targets = if (videos.isEmpty()) listOf(watchTargetFor(meta, null)) else videos.map { watchTargetFor(meta, it) }
    for (t in targets) {
        history.update(t) {
            if (watched) {
                it.copy(watched = true, upNext = false, hidden = false)
            } else {
                it.copy(watched = false, positionMs = 0, upNext = false)
            }
        }
    }

    // El siguiente episodio después del último marcado.
    val last = targets.last()
    val nextVideo = if (videos.isEmpty()) null else nextEpisodeVideo(meta.videos, last.videoId)
    val next = nextVideo?.let { watchTargetFor(meta, it) }
    if (next != null) {
        val existing = history.get(next.videoId)
        if (watched && (existing == null || (!existing.watched && existing.positionMs == 0L))) {
            history.update(next) { it.copy(upNext = true, hidden = false) }
        } else if (!watched && existing != null && existing.upNext && existing.positionMs == 0L) {
            // Ya no "toca" ese: deja de salir en "Continuar viendo".
            history.update(next) { it.copy(upNext = false) }
        }
    }

    // Tu cuenta de Stremio guarda un solo punto por título: el último que tocaste.
    val entry = history.get(last.videoId)
    sync.push(
        ProgressInput(
            metaId = meta.id,
            type = last.type,
            name = meta.name,
            poster = meta.poster,
            videoId = last.videoId,
            positionMs = 0,
            durationMs = entry?.durationMs ?: 0,
            watched = watched,
            nextVideoId = if (watched) next?.videoId else null,
            unwatch = !watched,
        ),
    )
}
