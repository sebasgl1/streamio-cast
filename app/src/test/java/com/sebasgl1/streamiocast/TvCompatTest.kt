package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.data.Stream
import com.sebasgl1.streamiocast.data.StreamFilter
import com.sebasgl1.streamiocast.data.StreamResult
import com.sebasgl1.streamiocast.data.Verdict
import com.sebasgl1.streamiocast.data.analyzeStream
import com.sebasgl1.streamiocast.data.classify
import com.sebasgl1.streamiocast.data.streamComparator
import com.sebasgl1.streamiocast.data.verdictFor
import com.sebasgl1.streamiocast.player.pickNextStream
import org.junit.Assert.assertEquals
import org.junit.Test

class TvCompatTest {

    private fun result(title: String, name: String = "Torrentio\n1080p"): StreamResult {
        val s = Stream(name = name, title = title, infoHash = title)
        val info = analyzeStream(s)
        return StreamResult("Test", s, info, classify(info))
    }

    @Test
    fun hevc4kHdrIsGoodOnTvButBadOnChromecast() {
        val r = result("Movie.2023.2160p.WEB-DL.x265.10bit.HDR.DDP5.1\n👤 300 💾 18 GB", "Torrentio\n4k HDR")
        assertEquals(Verdict.BAD, r.cls.verdict)
        assertEquals(Verdict.GOOD, r.tv.verdict)
        assertEquals(Verdict.GOOD, r.verdictFor(StreamFilter.ALL))
    }

    @Test
    fun av1IsBadOnTv() {
        assertEquals(Verdict.BAD, result("Movie.2023.1080p.WEB.AV1.Opus\n👤 50").tv.verdict)
    }

    @Test
    fun dtsOnlyIsDoubtfulOnTv() {
        assertEquals(Verdict.MAYBE, result("Movie.2010.1080p.BluRay.x264.DTS\n👤 50").tv.verdict)
        assertEquals(Verdict.GOOD, result("Movie.2010.1080p.BluRay.x264.DTS.AC3\n👤 50").tv.verdict)
    }

    @Test
    fun dolbyVisionOnlyIsDoubtful() {
        assertEquals(Verdict.MAYBE, result("Show.S01E01.2160p.WEB.DV.x265.DDP\n👤 50", "Torrentio\n4k DV").tv.verdict)
        assertEquals(Verdict.GOOD, result("Show.S01E01.2160p.WEB.DV.HDR10.x265.DDP\n👤 50", "Torrentio\n4k DV | HDR").tv.verdict)
    }

    @Test
    fun huge4kRemuxIsDoubtful() {
        val r = result("Movie.2019.2160p.UHD.BluRay.REMUX.HEVC.HDR.TrueHD.AC3\n👤 80 💾 65.2 GB", "Torrentio\n4k HDR")
        assertEquals(Verdict.MAYBE, r.tv.verdict)
    }

    @Test
    fun nextEpisodeOnTvPrefersTvCompatible() {
        val av1 = result("Show.S01E02.1080p.AV1\n👤 900")
        val hevc = result("Show.S01E02.2160p.x265.HDR.DDP\n👤 100", "Torrentio\n4k HDR")
        val list = listOf(av1, hevc).sortedWith(streamComparator)
        assertEquals(hevc, pickNextStream(list, null, device = StreamFilter.TV))
    }
}
