package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.data.AudioCodec
import com.sebasgl1.streamiocast.data.Stream
import com.sebasgl1.streamiocast.data.classifyTv
import com.sebasgl1.streamiocast.data.tvProfileFor
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
    fun av1DependsOnTheTvYear() {
        val av1 = result("Movie.2023.1080p.WEB.AV1.AAC\n👤 50").info
        assertEquals(Verdict.MAYBE, classifyTv(av1).verdict)
        assertEquals(Verdict.BAD, classifyTv(av1, tvProfileFor("Samsung", "UN43MU6100", "")).verdict)
        assertEquals(Verdict.GOOD, classifyTv(av1, tvProfileFor("Samsung", "UN55TU7000", "")).verdict)
    }

    @Test
    fun dtsDependsOnTheTv() {
        val dts = result("Movie.2010.1080p.BluRay.x264.DTS\n👤 50").info
        val mixed = result("Movie.2010.1080p.BluRay.x264.DTS.AC3\n👤 50").info
        // Sin TV elegido: dudoso (el de tu amiga podía ser de 2018+).
        assertEquals(Verdict.MAYBE, classifyTv(dts).verdict)
        assertEquals(Verdict.MAYBE, classifyTv(mixed).verdict)
        // Samsung 2017: DTS suena.
        val samsung2017 = tvProfileFor("Samsung Electronics", "UN43MU6100", "[TV] Samsung 6 Series (43)")
        assertEquals(Verdict.GOOD, classifyTv(dts, samsung2017).verdict)
        // Samsung 2018: DTS mudo; con AC3 de segunda pista, dudoso.
        val samsung2018 = tvProfileFor("Samsung Electronics", "UN55NU7100", "")
        assertEquals(Verdict.BAD, classifyTv(dts, samsung2018).verdict)
        assertEquals(Verdict.MAYBE, classifyTv(mixed, samsung2018).verdict)
    }

    @Test
    fun learnedMutedAudioTurnsRed() {
        val eac3 = result("Show.S01E01.1080p.WEB.H264.DDP5.1\n👤 50").info
        val profile = tvProfileFor("Hisense", "55A6", "", muted = setOf(AudioCodec.EAC3))
        assertEquals(Verdict.BAD, classifyTv(eac3, profile).verdict)
        assertEquals(Verdict.GOOD, classifyTv(eac3, tvProfileFor("Hisense", "55A6", "")).verdict)
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
