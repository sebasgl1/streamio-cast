package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.data.AudioCodec
import com.sebasgl1.streamiocast.data.Stream
import com.sebasgl1.streamiocast.data.StreamHints
import com.sebasgl1.streamiocast.data.StreamResult
import com.sebasgl1.streamiocast.data.Verdict
import com.sebasgl1.streamiocast.data.VideoCodec
import com.sebasgl1.streamiocast.data.analyzeStream
import com.sebasgl1.streamiocast.data.classify
import com.sebasgl1.streamiocast.data.streamComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamClassifierTest {

    private fun stream(
        title: String,
        name: String = "Torrentio\n1080p",
        filename: String? = null,
        bingeGroup: String? = null,
        url: String? = null,
        hash: String? = "abc",
    ) = Stream(
        name = name,
        title = title,
        infoHash = hash,
        url = url,
        behaviorHints = StreamHints(bingeGroup = bingeGroup, filename = filename),
    )

    private fun result(s: Stream): StreamResult {
        val info = analyzeStream(s)
        return StreamResult("Test", s, info, classify(info))
    }

    @Test
    fun h264Aac1080pIsGood() {
        val r = result(
            stream("The.Matrix.1999.1080p.BluRay.x264.AAC5.1-YIFY\n👤 1500 💾 2.1 GB ⚙️ YTS"),
        )
        assertEquals(Verdict.GOOD, r.cls.verdict)
        assertEquals(1080, r.info.resolution)
        assertEquals(VideoCodec.H264, r.info.codec)
        assertTrue(AudioCodec.AAC in r.info.audio)
        assertEquals(1500, r.info.seeders)
        assertEquals("YTS", r.info.provider)
        assertEquals("2.1 GB", r.info.sizeText)
        assertTrue((r.info.sizeBytes ?: 0L) > 2_000_000_000L)
        assertEquals("The.Matrix.1999.1080p.BluRay.x264.AAC5.1-YIFY", r.info.releaseName)
        assertFalse(r.cls.needsAudioConvert)
        assertTrue(r.cls.needsRemux)
    }

    @Test
    fun uhdHevcHdrIsBad() {
        val r = result(
            stream(
                title = "The.Matrix.1999.2160p.BluRay.HDR.DDP5.1.x265-GalaxyUHD\n👤 20 💾 15 GB ⚙️ 1337x",
                name = "Torrentio\n4k HDR",
            ),
        )
        assertEquals(Verdict.BAD, r.cls.verdict)
        assertEquals(2160, r.info.resolution)
        assertEquals(VideoCodec.HEVC, r.info.codec)
        assertTrue(r.info.hdr)
        assertTrue(AudioCodec.EAC3 in r.info.audio)
        assertTrue(r.cls.reasons.any { it.contains("4K") })
    }

    @Test
    fun webDlWithDolbyDigitalPlusIsMaybeForCast() {
        val r = result(stream("Movie.2020.1080p.WEB-DL.DDP5.1.H.264-GROUP\n👤 80 💾 3.4 GB ⚙️ EZTV"))
        assertEquals(Verdict.MAYBE, r.cls.verdict)
        assertEquals(VideoCodec.H264, r.info.codec)
        assertEquals(setOf(AudioCodec.EAC3), r.info.audio)
        assertTrue(r.cls.needsAudioConvert)
    }

    @Test
    fun codecNotStatedIsMaybe() {
        val r = result(stream("Movie.2019.1080p.BluRay.REMUX-GROUP\n👤 12 💾 25 GB ⚙️ Rutor"))
        assertEquals(VideoCodec.UNKNOWN, r.info.codec)
        assertEquals(Verdict.MAYBE, r.cls.verdict)
    }

    @Test
    fun hevcTenBitIsBad() {
        val r = result(stream("Movie.2021.720p.BluRay.x265.10bit-GROUP\n👤 40 💾 1.2 GB ⚙️ YTS", name = "Torrentio\n720p"))
        assertEquals(Verdict.BAD, r.cls.verdict)
        assertTrue(r.info.tenBit)
    }

    @Test
    fun h264TenBitIsBad() {
        val r = result(stream("Show.S01E01.1080p.WEB.x264.10bit-GROUP\n👤 9 💾 800 MB ⚙️ Nyaa"))
        assertEquals(VideoCodec.H264, r.info.codec)
        assertEquals(Verdict.BAD, r.cls.verdict)
        assertTrue(r.cls.reasons.any { it.contains("10 bits") })
    }

    @Test
    fun camRecordingIsBad() {
        val r = result(stream("Movie.2024.HDCAM.x264.AAC\n👤 300 💾 1.5 GB ⚙️ TPB", name = "Torrentio\nOther"))
        assertTrue(r.info.lowQuality)
        assertEquals(Verdict.BAD, r.cls.verdict)
    }

    @Test
    fun ac3AndEac3AreTold() {
        val ac3 = analyzeStream(stream("Movie 720p x264 AC3 5.1"))
        assertEquals(setOf(AudioCodec.AC3), ac3.audio)

        val eac3 = analyzeStream(stream("Movie 720p x264 E-AC-3 5.1"))
        assertEquals(setOf(AudioCodec.EAC3), eac3.audio)

        val dd = analyzeStream(stream("Movie 720p x264 DD5.1"))
        assertEquals(setOf(AudioCodec.AC3), dd.audio)
    }

    @Test
    fun dtsNeedsConversion() {
        val r = result(stream("Movie.2018.1080p.BluRay.x264.DTS-HD.MA.5.1-GROUP\n👤 55 💾 9 GB ⚙️ TPB"))
        assertTrue(AudioCodec.DTS in r.info.audio)
        assertTrue(r.cls.needsAudioConvert)
        assertEquals(Verdict.MAYBE, r.cls.verdict)
    }

    @Test
    fun bingeGroupAndFilenameFeedTheAnalysis() {
        val r = result(
            stream(
                title = "Some release without tags",
                bingeGroup = "torrentio|1080p|BluRay|x265|10bit|HDR",
                filename = "release.mkv",
            ),
        )
        assertEquals(VideoCodec.HEVC, r.info.codec)
        assertEquals("mkv", r.info.container)
        assertEquals(Verdict.BAD, r.cls.verdict)
    }

    @Test
    fun directMp4LinkNeedsNoRemux() {
        val r = result(
            stream(
                title = "Movie 1080p x264 AAC",
                url = "https://cdn.example.com/video.mp4?token=1",
                hash = null,
            ),
        )
        assertEquals("mp4", r.info.container)
        assertFalse(r.cls.needsRemux)
        assertNull(r.stream.infoHash)
        assertEquals(Verdict.GOOD, r.cls.verdict)
    }

    @Test
    fun listIsSortedCompatibleFirstAndLowSeedersLast() {
        val bad = result(stream("Movie.2160p.x265.HDR\n👤 900 💾 20 GB ⚙️ A", name = "Torrentio\n4k HDR"))
        val maybe = result(stream("Movie.1080p.BluRay.REMUX\n👤 50 💾 20 GB ⚙️ B"))
        val goodLowSeeders = result(stream("Movie.1080p.BluRay.x264.AAC\n👤 1 💾 2 GB ⚙️ C"))
        val good720Popular = result(stream("Movie.720p.BluRay.x264.AAC\n👤 200 💾 1 GB ⚙️ D", name = "Torrentio\n720p"))
        val good1080Popular = result(stream("Movie.1080p.WEB.x264.AAC\n👤 100 💾 2 GB ⚙️ E"))

        val sorted = listOf(bad, maybe, goodLowSeeders, good720Popular, good1080Popular)
            .sortedWith(streamComparator)

        assertEquals(
            listOf(good1080Popular, good720Popular, goodLowSeeders, maybe, bad),
            sorted,
        )
    }
}
