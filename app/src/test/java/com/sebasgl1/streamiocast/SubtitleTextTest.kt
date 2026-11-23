package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.subtitles.HASH_CHUNK
import com.sebasgl1.streamiocast.subtitles.SubCue
import com.sebasgl1.streamiocast.subtitles.decodeSubtitleBytes
import com.sebasgl1.streamiocast.subtitles.languageName
import com.sebasgl1.streamiocast.subtitles.normalizeLang
import com.sebasgl1.streamiocast.subtitles.openSubtitlesHash
import com.sebasgl1.streamiocast.subtitles.parseSubtitles
import com.sebasgl1.streamiocast.subtitles.parseTimestamp
import com.sebasgl1.streamiocast.subtitles.toWebVtt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTextTest {

    @Test
    fun timestamps() {
        assertEquals(3_723_456L, parseTimestamp("01:02:03,456"))
        assertEquals(3_723_456L, parseTimestamp("01:02:03.456"))
        assertEquals(63_500L, parseTimestamp("01:03.5")) // WebVTT sin horas, fracción corta
        assertNull(parseTimestamp("hola"))
    }

    @Test
    fun parsesSrtWithTagsAndCrlf() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Hola</i>, {\\an8}mundo\r\nsegunda línea\r\n\r\n" +
            "2\r\n00:00:04,000 --> 00:00:05,000\r\nAdiós &amp; gracias\r\n"
        val cues = parseSubtitles(srt)
        assertEquals(2, cues.size)
        assertEquals(SubCue(1000, 3500, "Hola, mundo\nsegunda línea"), cues[0])
        assertEquals("Adiós & gracias", cues[1].text)
    }

    @Test
    fun parsesWebVtt() {
        val vtt = "WEBVTT\n\nSTYLE\n::cue { color: yellow }\n\nintro\n00:01.000 --> 00:02.000 align:start\nUno\n\n" +
            "00:00:03.000 --> 00:00:04.000\nDos\n"
        val cues = parseSubtitles(vtt)
        assertEquals(listOf("Uno", "Dos"), cues.map { it.text })
        assertEquals(1000L, cues[0].startMs)
    }

    @Test
    fun toleratesMissingBlankLines() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nUno\n2\n00:00:03,000 --> 00:00:04,000\nDos\n"
        val cues = parseSubtitles(srt)
        assertEquals(listOf("Uno", "Dos"), cues.map { it.text })
    }

    @Test
    fun webVttOutputWithOffset() {
        val vtt = toWebVtt(listOf(SubCue(1000, 2500, "Hola")), offsetMs = 500)
        assertEquals("WEBVTT\n\n00:00:01.500 --> 00:00:03.000\nHola\n\n", vtt)
        // Lo que queda antes de cero con un retraso negativo se descarta.
        assertEquals("WEBVTT\n\n", toWebVtt(listOf(SubCue(0, 400, "x")), offsetMs = -500))
    }

    @Test
    fun decodesUtf8AndWindows1252() {
        assertEquals("canción", decodeSubtitleBytes("canción".toByteArray(Charsets.UTF_8)))
        val cp1252 = "canción ñandú".toByteArray(charset("windows-1252"))
        assertEquals("canción ñandú", decodeSubtitleBytes(cp1252))
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "sí".toByteArray()
        assertEquals("sí", decodeSubtitleBytes(withBom))
    }

    @Test
    fun openSubtitlesHashMath() {
        val zeros = ByteArray(HASH_CHUNK)
        assertEquals("0000000000020000", openSubtitlesHash(131_072, zeros, zeros))
        val head = ByteArray(HASH_CHUNK).also { it[0] = 1 } // little-endian: suma 1
        assertEquals("0000000000020001", openSubtitlesHash(131_072, head, zeros))
        // Desborda en 64 bits como el algoritmo original.
        val ff = ByteArray(HASH_CHUNK) { 0xFF.toByte() }
        assertEquals(16, openSubtitlesHash(131_072, ff, ff).length)
    }

    @Test
    fun languages() {
        assertEquals("es", normalizeLang("spa"))
        assertEquals("es", normalizeLang("es-419"))
        assertEquals("pt", normalizeLang("pob"))
        assertEquals("fr", normalizeLang("fre"))
        assertEquals("de", normalizeLang("ger"))
        assertEquals("en", normalizeLang("eng"))
        assertNull(normalizeLang("und"))
        assertEquals("Español", languageName("spa"))
        assertEquals("Inglés", languageName("en"))
        assertEquals("Portugués (Brasil)", languageName("pob", "pob"))
        assertTrue(languageName(null, "Klingon") == "Klingon")
    }
}

class GzipSubtitleTest {
    @org.junit.Test
    fun gzippedSrtIsDecoded() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHola\n"
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(srt.toByteArray()) }
        val cues = com.sebasgl1.streamiocast.subtitles.parseSubtitles(
            com.sebasgl1.streamiocast.subtitles.decodeSubtitleBytes(out.toByteArray()),
        )
        org.junit.Assert.assertEquals("Hola", cues.single().text)
    }
}
