package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.data.Stream
import com.sebasgl1.streamiocast.data.StreamResult
import com.sebasgl1.streamiocast.data.analyzeStream
import com.sebasgl1.streamiocast.data.classify
import com.sebasgl1.streamiocast.data.detectLanguages
import com.sebasgl1.streamiocast.data.languageFamilies
import com.sebasgl1.streamiocast.data.matchesQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLanguagesTest {

    @Test
    fun flagsAndWords() {
        assertEquals(setOf("es-la", "en"), detectLanguages("Movie.2023.1080p.WEB\n👤 50 💾 2 GB\n🇲🇽 / 🇬🇧"))
        assertEquals(setOf("es-es"), detectLanguages("Pelicula.2020.1080p.Castellano.x264"))
        assertEquals(setOf("es-la", "multi"), detectLanguages("Movie 2019 DUAL Latino 1080p"))
        assertEquals(setOf("es"), detectLanguages("Movie.2019.Spanish.1080p"))
        assertTrue(detectLanguages("Movie.2023.1080p.WEB-DL.x264.AAC").isEmpty())
        // "Spanish" genérico sobra si ya dice latino.
        assertEquals(setOf("es-la"), detectLanguages("Movie Spanish Latino 720p"))
    }

    @Test
    fun familiesAndSearch() {
        val s = Stream(name = "Torrentio\n1080p", title = "La.Odisea.1080p.Latino.x264\n👤 30 💾 2 GB", infoHash = "a")
        val info = analyzeStream(s)
        assertEquals(setOf("es"), info.languageFamilies)
        val r = StreamResult("Test", s, info, classify(info))
        assertTrue(r.matchesQuery("latino"))
        assertTrue(r.matchesQuery("ESPAÑOL 1080"))
        assertFalse(r.matchesQuery("castellano"))
    }
}
