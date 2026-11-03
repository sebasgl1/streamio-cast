package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.stream.LocalStreamServer
import com.sebasgl1.streamiocast.stream.StreamSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/** Arranca el servidor de verdad (sockets locales) y le hace peticiones HTTP. */
class LocalStreamServerTest {

    /** Fuente en memoria que entrega como máximo [chunk] bytes por lectura, como un torrent. */
    private class BytesSource(private val data: ByteArray, private val chunk: Int = 7_000) : StreamSource {
        override val length: Long = data.size.toLong()
        override val mimeType: String = "video/mp4"

        override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int, isCancelled: () -> Boolean): Int {
            if (position >= length) return -1
            val n = minOf(len.toLong(), chunk.toLong(), length - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
    }

    private val data = ByteArray(300_000) { (it % 251).toByte() }
    private lateinit var server: LocalStreamServer
    private lateinit var url: String

    @Before
    fun setUp() {
        server = LocalStreamServer()
        server.start()
        url = server.localUrl(server.register(BytesSource(data), "Mi película.mp4"))
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun open(range: String? = null, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            if (range != null) setRequestProperty("Range", range)
        }

    @Test
    fun fullGet() {
        val c = open()
        assertEquals(200, c.responseCode)
        assertEquals("video/mp4", c.contentType)
        assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
        assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
        assertArrayEquals(data, c.inputStream.use { it.readBytes() })
    }

    @Test
    fun partialGet() {
        val c = open("bytes=1000-50999")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 1000-50999/300000", c.getHeaderField("Content-Range"))
        assertEquals("50000", c.getHeaderField("Content-Length"))
        assertArrayEquals(data.copyOfRange(1000, 51000), c.inputStream.use { it.readBytes() })
    }

    @Test
    fun tailForContainerIndex() {
        val c = open("bytes=-100")
        assertEquals(206, c.responseCode)
        assertArrayEquals(data.copyOfRange(data.size - 100, data.size), c.inputStream.use { it.readBytes() })
    }

    @Test
    fun headReportsLength() {
        val c = open(method = "HEAD")
        assertEquals(200, c.responseCode)
        assertEquals("300000", c.getHeaderField("Content-Length"))
    }

    @Test
    fun rangePastTheEndIs416() {
        val c = open("bytes=400000-")
        assertEquals(416, c.responseCode)
        assertEquals("bytes */300000", c.getHeaderField("Content-Range"))
    }

    @Test
    fun unknownTokenIs404() {
        val c = (URL("http://127.0.0.1:${server.port}/stream/nope/x.mp4").openConnection() as HttpURLConnection)
        assertEquals(404, c.responseCode)
    }

    @Test
    fun unregisteredSourceDisappears() {
        val path = server.register(BytesSource(data), "otra.mkv")
        server.unregister(path)
        val c = (URL(server.localUrl(path)).openConnection() as HttpURLConnection)
        assertEquals(404, c.responseCode)
    }

    @Test
    fun clientClosingEarlyDoesNotBreakTheServer() {
        val c = open("bytes=0-")
        c.inputStream.use { it.read(ByteArray(min(1024, data.size))) }
        c.disconnect()
        // El servidor sigue atendiendo después de que un cliente se fue a mitad.
        assertEquals(206, open("bytes=10-19").responseCode)
    }
}
