package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.data.AudioCodec
import com.sebasgl1.streamiocast.data.CastProfile
import com.sebasgl1.streamiocast.data.Stream
import com.sebasgl1.streamiocast.data.Support
import com.sebasgl1.streamiocast.data.Verdict
import com.sebasgl1.streamiocast.data.analyzeStream
import com.sebasgl1.streamiocast.data.audioCodecOfMime
import com.sebasgl1.streamiocast.data.castProfileFor
import com.sebasgl1.streamiocast.data.classify
import com.sebasgl1.streamiocast.data.lgYear
import com.sebasgl1.streamiocast.data.samsungYear
import com.sebasgl1.streamiocast.data.tvProfileFor
import com.sebasgl1.streamiocast.player.VideoFacts
import com.sebasgl1.streamiocast.player.checkCastCompat
import com.sebasgl1.streamiocast.player.checkTvCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun samsungYears() {
        assertEquals(2017, samsungYear("UN43MU6100"))
        assertEquals(2018, samsungYear("UE55NU7100"))
        assertEquals(2019, samsungYear("QN65Q80RAFXZA"))
        assertEquals(2021, samsungYear("QN65QN90AAFXZA"))
        assertEquals(2024, samsungYear("QE55S90DAEXXU"))
        assertEquals(2023, samsungYear("UN43CU7000"))
        assertEquals(2025, samsungYear("UN43U8000F"))
        assertEquals(2022, samsungYear("QN55LS03BAFXZA"))
        assertNull(samsungYear("[TV] Samsung 6 Series (43)"))
    }

    @Test
    fun lgYears() {
        assertEquals(2020, lgYear("OLED55CXPUA"))
        assertEquals(2019, lgYear("OLED65C9PUA"))
        assertEquals(2019, lgYear("43UM7300PUA"))
        assertEquals(2021, lgYear("55NANO85UPA"))
        assertEquals(2018, lgYear("55SK8000"))
        assertEquals(2022, lgYear("[LG] webOS TV UQ7570PUJ"))
    }

    @Test
    fun tvProfiles() {
        val mine = tvProfileFor("Samsung Electronics", "UN43MU6100", "[TV] Samsung 6 Series (43)")
        assertEquals("Samsung 2017", mine.label)
        assertEquals(Support.YES, mine.dts)
        assertEquals(Support.NO, tvProfileFor("Samsung Electronics", "UN50TU8000", "").dts)
        assertEquals(Support.NO, tvProfileFor("LG Electronics.", "OLED55CXPUA", "").dts)
        assertEquals(Support.YES, tvProfileFor("LG Electronics.", "OLED55C2PUA", "").dts)
        assertEquals(Support.NO, tvProfileFor("LG Electronics.", "OLED55C5PUA", "").dts)
        assertEquals(Support.UNKNOWN, tvProfileFor("Hisense", "55A6", "").dts)
    }

    @Test
    fun chromecastModels() {
        assertFalse(castProfileFor("Chromecast").advanced)
        assertFalse(castProfileFor(null).advanced)
        assertTrue(castProfileFor("Chromecast Ultra").advanced)
        assertTrue(castProfileFor("Chromecast HD").advanced.not())
        assertTrue(castProfileFor("Google TV Streamer").av1)
    }

    @Test
    fun advancedChromecastAcceptsHevc4k() {
        val info = analyzeStream(Stream(name = "Torrentio\n4k HDR", title = "Movie.2160p.WEB.x265.10bit.HDR.DDP5.1\n👤 50"))
        assertEquals(Verdict.BAD, classify(info).verdict)
        assertEquals(Verdict.GOOD, classify(info, castProfileFor("Chromecast with Google TV")).verdict)
    }

    @Test
    fun realFileChecks() {
        val video = VideoFacts("video/avc", 1920, 1080, "avc1.640028", 24f)
        // DTS real en un Samsung 2018: bloquea con un aviso claro.
        val samsung2018 = tvProfileFor("Samsung", "UN55NU7100", "")
        val dts = checkTvCompat(video, "audio/vnd.dts", samsung2018)
        assertTrue(dts.blocking.single().contains("DTS"))
        // En tu Samsung 2017, DTS pasa.
        assertTrue(checkTvCompat(video, "audio/vnd.dts", tvProfileFor("Samsung", "UN43MU6100", "")).blocking.isEmpty())
        // TV desconocido: aviso, no bloqueo.
        val unknown = checkTvCompat(video, "audio/vnd.dts")
        assertTrue(unknown.blocking.isEmpty() && unknown.warnings.isNotEmpty())
        // Aprendido: E-AC3 mudo en este Chromecast.
        val learned = checkCastCompat(video, "audio/eac3", CastProfile.GEN2.copy(muted = setOf(AudioCodec.EAC3)))
        assertTrue(learned.blocking.isNotEmpty())
        assertEquals(AudioCodec.DTS, audioCodecOfMime("audio/vnd.dts.hd"))
    }
}
