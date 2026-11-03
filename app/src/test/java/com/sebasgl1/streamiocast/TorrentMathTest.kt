package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.torrent.PieceSpan
import com.sebasgl1.streamiocast.torrent.buildMagnet
import com.sebasgl1.streamiocast.torrent.isVideoFile
import com.sebasgl1.streamiocast.torrent.pickFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentMathTest {

    @Test
    fun pieceMathForFileInTheMiddleOfTheTorrent() {
        // Archivo que empieza en el byte 1500 de un torrent con piezas de 1000 bytes.
        val span = PieceSpan(fileOffset = 1500, fileLength = 4000, pieceLength = 1000)
        assertEquals(1, span.firstPiece)
        assertEquals(5, span.lastPiece) // último byte: 1500 + 3999 = 5499
        assertEquals(1, span.pieceAt(0))
        assertEquals(2, span.pieceAt(500))
        assertEquals(500L, span.bytesLeftInPiece(0)) // 1500 → 2000
        assertEquals(1000L, span.bytesLeftInPiece(500)) // 2000 → 3000
        assertEquals(1L, span.bytesLeftInPiece(3999)) // último byte del archivo
    }

    @Test
    fun pickFileUsesAddonIndexWhenItIsAVideo() {
        val names = listOf("sample.mkv", "Movie.mkv", "info.nfo")
        val sizes = listOf(50L, 5000L, 1L)
        assertEquals(1, pickFile(names, sizes, fileIdx = 1, filename = null))
        assertEquals(0, pickFile(names, sizes, fileIdx = 0, filename = null))
    }

    @Test
    fun pickFileFallsBackToFilenameThenLargestVideo() {
        val names = listOf("info.nfo", "Ep01.mkv", "Ep02.mkv")
        val sizes = listOf(1L, 900L, 1000L)
        assertEquals(1, pickFile(names, sizes, fileIdx = 0, filename = "ep01.MKV"))
        assertEquals(2, pickFile(names, sizes, fileIdx = 99, filename = null))
        assertNull(pickFile(listOf("a.txt"), listOf(1L), fileIdx = null, filename = null))
    }

    @Test
    fun videoExtensions() {
        assertTrue(isVideoFile("A.Movie.2020.MKV"))
        assertTrue(isVideoFile("clip.mp4"))
        assertFalse(isVideoFile("subs.srt"))
        assertFalse(isVideoFile(".pad/1234"))
    }

    @Test
    fun magnetIncludesHashAndEncodedTrackers() {
        val magnet = buildMagnet("ABCDEF0123", listOf("udp://tracker.example:1337/announce"))
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:abcdef0123"))
        assertTrue(magnet.contains("&tr=udp%3A%2F%2Ftracker.example%3A1337%2Fannounce"))
        // Siempre añade trackers públicos para encontrar peers más rápido.
        assertTrue(magnet.contains("opentrackr"))
    }
}
