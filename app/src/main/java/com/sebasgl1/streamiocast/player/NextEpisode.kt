package com.sebasgl1.streamiocast.player

import com.sebasgl1.streamiocast.data.StreamFilter
import com.sebasgl1.streamiocast.data.StreamResult
import com.sebasgl1.streamiocast.data.Verdict
import com.sebasgl1.streamiocast.data.streamComparator
import com.sebasgl1.streamiocast.data.Video

/**
 * El episodio que sigue a [currentId] en la lista de la serie (por temporada y número).
 * Los especiales (temporada 0) solo se encadenan entre sí.
 */
fun nextEpisodeVideo(videos: List<Video>, currentId: String): Video? {
    val current = videos.firstOrNull { it.id == currentId } ?: return null
    val currentSeason = current.season ?: return null
    val ordered = videos
        .filter { it.season != null && it.episode != null }
        .filter { if (currentSeason == 0) it.season == 0 else it.season!! > 0 }
        .sortedWith(compareBy({ it.season }, { it.episode }))
    val index = ordered.indexOfFirst { it.id == currentId }
    if (index < 0) return null
    return ordered.getOrNull(index + 1)
}

/**
 * Como Stremio: para el siguiente episodio se usa el stream del mismo "grupo" (misma calidad y
 * fuente, por ejemplo "torrentio|1080p|WEB-DL"). Si no hay, el mejor de la lista (ya viene ordenada).
 * Si se está transmitiendo, se descartan los que no sirven en ese aparato (Chromecast o TV).
 */
fun pickNextStream(results: List<StreamResult>, bingeGroup: String?, device: StreamFilter): StreamResult? {
    val pool = when (device) {
        StreamFilter.ALL -> results
        StreamFilter.CHROMECAST -> results.filter { it.cls.verdict != Verdict.BAD }
        StreamFilter.TV -> results.filter { it.tv.verdict != Verdict.BAD }.sortedWith(streamComparator(StreamFilter.TV))
    }
    if (bingeGroup != null) {
        pool.firstOrNull { it.stream.behaviorHints?.bingeGroup == bingeGroup }?.let { return it }
    }
    return pool.firstOrNull()
}
