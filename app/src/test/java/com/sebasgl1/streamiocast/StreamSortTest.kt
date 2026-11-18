package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.data.Stream
import com.sebasgl1.streamiocast.data.StreamFilter
import com.sebasgl1.streamiocast.data.StreamResult
import com.sebasgl1.streamiocast.data.StreamSort
import com.sebasgl1.streamiocast.data.analyzeStream
import com.sebasgl1.streamiocast.data.arrangeStreams
import com.sebasgl1.streamiocast.data.classify
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSortTest {

    private fun r(id: String, title: String, name: String = "Torrentio\n1080p"): StreamResult {
        val s = Stream(name = name, title = title, infoHash = id)
        val info = analyzeStream(s)
        return StreamResult("Test", s, info, classify(info))
    }

    // Chromecast: verde = H.264 AAC 1080p; amarillo = sin códec; rojo = HEVC 4K.
    private val greenFew = r("a", "Movie.1080p.WEB.x264.AAC\n👤 40 💾 2 GB")
    private val greenMany = r("b", "Movie.720p.WEB.x264.AAC\n👤 300 💾 1 GB", "Torrentio\n720p")
    private val yellowTop = r("c", "Movie.1080p.WEB.AAC\n👤 900 💾 3 GB")
    private val redTop = r("d", "Movie.2160p.WEB.x265.HDR.AAC\n👤 5000 💾 15 GB", "Torrentio\n4k")
    private val noSeeds = r("e", "Movie.1080p.WEB.x264.AAC\n💾 500 MB")
    private val all = listOf(greenFew, greenMany, yellowTop, redTop, noSeeds)

    private fun ids(list: List<StreamResult>) = list.map { it.stream.infoHash }

    @Test
    fun chromecastPlusSeedsPutsTheMostSeededCompatibleFirst() {
        val list = arrangeStreams(all, StreamFilter.CHROMECAST, StreamSort.SEEDS, descending = true)
        // Sin el rojo; verdes por seeds, luego el amarillo; sin seeds al final de su grupo.
        assertEquals(listOf("b", "a", "e", "c"), ids(list))
    }

    @Test
    fun allPlusSeedsIsPureSeedOrder() {
        val list = arrangeStreams(all, StreamFilter.ALL, StreamSort.SEEDS, descending = true)
        assertEquals(listOf("d", "c", "b", "a", "e"), ids(list))
        // Al invertir, los que no traen seeds siguen al final.
        val up = arrangeStreams(all, StreamFilter.ALL, StreamSort.SEEDS, descending = false)
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(up))
    }

    @Test
    fun sizeAndResolution() {
        val light = arrangeStreams(all, StreamFilter.ALL, StreamSort.SIZE, descending = false)
        assertEquals(listOf("e", "b", "a", "c", "d"), ids(light))
        val res = arrangeStreams(all, StreamFilter.ALL, StreamSort.RESOLUTION, descending = true)
        // 4K, luego los 1080p (desempate por seeds: c 900, a 40, e sin seeds), luego 720p.
        assertEquals(listOf("d", "c", "a", "e", "b"), ids(res))
    }
}
