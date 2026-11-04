package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.data.Stream
import com.sebasgl1.streamiocast.data.StreamHints
import com.sebasgl1.streamiocast.data.StreamResult
import com.sebasgl1.streamiocast.data.Video
import com.sebasgl1.streamiocast.data.analyzeStream
import com.sebasgl1.streamiocast.data.classify
import com.sebasgl1.streamiocast.data.streamComparator
import com.sebasgl1.streamiocast.player.nextEpisodeVideo
import com.sebasgl1.streamiocast.player.pickNextStream
import com.sebasgl1.streamiocast.torrent.formatBytes
import com.sebasgl1.streamiocast.torrent.hasRoomFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeTest {

    private fun v(id: String, season: Int, episode: Int) = Video(id = id, season = season, episode = episode)

    private val videos = listOf(
        v("s:2:1", 2, 1),
        v("s:1:2", 1, 2),
        v("s:0:1", 0, 1),
        v("s:1:1", 1, 1),
        v("s:0:2", 0, 2),
    )

    @Test
    fun nextWithinAndAcrossSeasons() {
        assertEquals("s:1:2", nextEpisodeVideo(videos, "s:1:1")?.id)
        assertEquals("s:2:1", nextEpisodeVideo(videos, "s:1:2")?.id) // salta los especiales
        assertNull(nextEpisodeVideo(videos, "s:2:1")) // último
        assertEquals("s:0:2", nextEpisodeVideo(videos, "s:0:1")?.id)
        assertNull(nextEpisodeVideo(videos, "otra"))
    }

    private fun result(title: String, group: String?, name: String = "Torrentio\n1080p"): StreamResult {
        val s = Stream(name = name, title = title, infoHash = title, behaviorHints = StreamHints(bingeGroup = group))
        val info = analyzeStream(s)
        return StreamResult("Test", s, info, classify(info))
    }

    @Test
    fun picksSameBingeGroupFirst() {
        val a = result("Show.S01E02.1080p.WEB.x264.AAC\n👤 500", "torrentio|1080p|WEB")
        val b = result("Show.S01E02.1080p.BluRay.x264.AAC\n👤 20", "torrentio|1080p|BluRay")
        val list = listOf(a, b).sortedWith(streamComparator)
        assertEquals(b, pickNextStream(list, "torrentio|1080p|BluRay", requireCompatible = false))
        assertEquals(a, pickNextStream(list, "otro-grupo", requireCompatible = false))
        assertEquals(a, pickNextStream(list, null, requireCompatible = false))
    }

    @Test
    fun skipsIncompatibleWhenCasting() {
        val hevc = result("Show.S01E02.2160p.x265.HDR\n👤 900", "g", name = "Torrentio\n4k HDR")
        val ok = result("Show.S01E02.720p.x264.AAC\n👤 10", "h", name = "Torrentio\n720p")
        val list = listOf(hevc, ok).sortedWith(streamComparator)
        assertEquals(ok, pickNextStream(list, "g", requireCompatible = true))
        assertEquals(hevc, pickNextStream(listOf(hevc, ok), "g", requireCompatible = false))
        assertNull(pickNextStream(listOf(hevc), null, requireCompatible = true))
    }

    @Test
    fun bytesAndStorage() {
        assertEquals("2.1 GB", formatBytes(2_254_857_830))
        assertEquals("850 MB", formatBytes(850L * 1024 * 1024))
        assertTrue(hasRoomFor(1L shl 30, 2L shl 30))
        assertFalse(hasRoomFor(1L shl 30, (1L shl 30) + 100))
    }
}
