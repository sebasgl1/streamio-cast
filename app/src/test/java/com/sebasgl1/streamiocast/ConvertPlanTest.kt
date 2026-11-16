package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.data.AudioCodec
import com.sebasgl1.streamiocast.data.CastProfile
import com.sebasgl1.streamiocast.data.castProfileFor
import com.sebasgl1.streamiocast.data.tvProfileFor
import com.sebasgl1.streamiocast.player.VideoFacts
import com.sebasgl1.streamiocast.transcode.AudioPick
import com.sebasgl1.streamiocast.transcode.AudioTarget
import com.sebasgl1.streamiocast.transcode.LiveContainer
import com.sebasgl1.streamiocast.transcode.VideoTarget
import com.sebasgl1.streamiocast.transcode.planForCast
import com.sebasgl1.streamiocast.transcode.planForTv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertPlanTest {

    private val h264 = VideoFacts("video/avc", 1920, 1080, "avc1.640028", 24f)
    private val hevc4k = VideoFacts("video/hevc", 3840, 2160, "hvc1.2.4.L150", 24f)
    private val samsung2018 = tvProfileFor("Samsung", "UN55NU7100", "")
    private val samsung2017 = tvProfileFor("Samsung", "UN43MU6100", "")

    private fun dts(index: Int = 0, default: Boolean = true) = AudioPick(index, "audio/vnd.dts", "Inglés", default)
    private fun ac3(index: Int, default: Boolean = false) = AudioPick(index, "audio/ac3", "Español", default)

    @Test
    fun dtsOnATvWithoutDtsBecomesAc3AndVideoIsCopied() {
        val plan = planForTv(h264, listOf(dts()), 0, samsung2018)!!
        assertEquals(AudioTarget.AC3, plan.audio)
        assertEquals(VideoTarget.COPY, plan.video)
        assertEquals(LiveContainer.MPEGTS, plan.container)
        val args = plan.ffmpegArgs("http://127.0.0.1:5000/stream/t/a.mkv", "/cache/out.ts", 0)
        assertTrue(args.containsAll(listOf("-c:v", "copy", "-c:a", "ac3", "-f", "mpegts")))
        assertEquals("0:a:0", args[args.indexOf("-map") + 3])
        assertFalse("-ss" in args)
        assertEquals("/cache/out.ts", args.last())
        assertTrue(plan.describe("tu Samsung").contains("DTS"))
        assertTrue(plan.describe("tu Samsung").contains("sin perder calidad"))
    }

    @Test
    fun nothingToConvertWhenTheTvPlaysIt() {
        assertNull(planForTv(h264, listOf(dts()), 0, samsung2017))
        assertNull(planForTv(h264, listOf(ac3(0, default = true)), 0, samsung2018))
    }

    @Test
    fun chosenTrackThatAlreadyWorksIsSentWithoutConverting() {
        // El TV usaría la primera (DTS, muda); en la app está elegida la segunda (AC3 en español).
        val plan = planForTv(h264, listOf(dts(0), ac3(1)), 1, samsung2018)!!
        assertEquals(AudioTarget.COPY, plan.audio)
        assertEquals(1, plan.audioIndex)
        assertTrue(plan.audioIsOtherTrack)
        assertTrue(plan.ffmpegArgs("in", "out", 0).containsAll(listOf("-c:a", "copy")))
        assertTrue(plan.describe("tu TV").contains("Español"))
    }

    @Test
    fun learnedMutedAc3FallsBackToAac() {
        val tv = tvProfileFor("Hisense", "55A6", "", muted = setOf(AudioCodec.AC3, AudioCodec.DTS))
        val plan = planForTv(h264, listOf(dts()), 0, tv)!!
        assertEquals(AudioTarget.AAC, plan.audio)
    }

    @Test
    fun seekingRestartsFromThatPoint() {
        val plan = planForTv(h264, listOf(dts()), 0, samsung2018)!!
        val args = plan.ffmpegArgs("in", "out", 754_500)
        assertEquals("754.500", args[args.indexOf("-ss") + 1])
        assertTrue(args.indexOf("-ss") < args.indexOf("-i"))
    }

    @Test
    fun oldChromecastGetsVideoAndAudioConverted() {
        val plan = planForCast(hevc4k, listOf(dts()), 0, CastProfile.GEN2)!!
        assertEquals(VideoTarget.H264, plan.video)
        assertEquals(1080, plan.scaleToHeight)
        assertEquals(AudioTarget.AAC, plan.audio)
        assertEquals(LiveContainer.FMP4, plan.container)
        val args = plan.ffmpegArgs("in", "out.mp4", 0)
        assertTrue(args.containsAll(listOf("-c:v", "h264_mediacodec", "-c:a", "aac", "-f", "mp4")))
        assertTrue(args[args.indexOf("-movflags") + 1].contains("delay_moov"))
        assertTrue(args[args.indexOf("-vf") + 1].startsWith("scale=-2:1080"))
        assertTrue(plan.describe("tu Chromecast").contains("puede ir a saltos"))
        // Respaldo si el chip del celular falla: codificador por software.
        val soft = plan.copy(softwareVideo = true).ffmpegArgs("in", "out.mp4", 0)
        assertTrue(soft.containsAll(listOf("-c:v", "libx264", "-preset", "ultrafast")))
    }

    @Test
    fun chromecastOnlyAudioWhenVideoIsFine() {
        val plan = planForCast(h264, listOf(AudioPick(0, "audio/ac3", null, true)), 0, CastProfile.GEN2)!!
        assertEquals(VideoTarget.COPY, plan.video)
        assertEquals(AudioTarget.AAC, plan.audio)
        assertNull(planForCast(h264, listOf(AudioPick(0, "audio/mp4a-latm", null, true)), 0, CastProfile.GEN2))
        // Un Chromecast con Google TV ya reproduce HEVC 4K con AC3: nada que convertir.
        assertNull(planForCast(hevc4k, listOf(AudioPick(0, "audio/ac3", null, true)), 0, castProfileFor("Chromecast with Google TV")))
    }

    @Test
    fun av1IsConvertedOnlyWhereItIsNotSupported() {
        val av1 = VideoFacts("video/av01", 3840, 2160, null, 24f)
        val aac = listOf(AudioPick(0, "audio/mp4a-latm", null, true))
        assertEquals(VideoTarget.H264, planForCast(av1, aac, 0, CastProfile.GEN2)!!.video)
        // Samsung 2018 no lee AV1: vídeo a H.264 1080p y, de paso, el DTS a AC3.
        val old = planForTv(av1, listOf(dts()), 0, samsung2018)!!
        assertEquals(VideoTarget.H264, old.video)
        assertEquals(1080, old.scaleToHeight)
        assertEquals(AudioTarget.AC3, old.audio)
        // Samsung 2020 sí lee AV1: solo el audio.
        assertEquals(VideoTarget.COPY, planForTv(av1, listOf(dts()), 0, tvProfileFor("Samsung", "UN50TU8000", ""))!!.video)
        assertNull(planForTv(av1, aac, 0, tvProfileFor("Samsung", "UN50TU8000", "")))
    }
}
