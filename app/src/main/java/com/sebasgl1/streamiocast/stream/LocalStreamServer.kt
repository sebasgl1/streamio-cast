package com.sebasgl1.streamiocast.stream

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

/** Algo que se puede servir por HTTP mientras todavía se está descargando. */
interface StreamSource {
    val length: Long
    val mimeType: String

    /**
     * Lee hasta [len] bytes desde [position]. Bloquea hasta que haya datos disponibles.
     * Devuelve -1 al final del recurso. [isCancelled] indica si el cliente ya se fue.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, len: Int, isCancelled: () -> Boolean): Int
}

/**
 * Servidor HTTP mínimo con soporte de Range (necesario para adelantar/atrasar).
 *
 * Escucha en todas las interfaces: el reproductor del celular entra por 127.0.0.1 y el
 * Chromecast (etapa 4) por la IP de la red local. Cada recurso tiene un token aleatorio en la ruta.
 * Es Java puro (sin Android) para poder probarlo con tests de JVM.
 */
class LocalStreamServer {
    private val sources = ConcurrentHashMap<String, StreamSource>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "stream-http").apply { isDaemon = true }
    }

    @Volatile
    private var server: ServerSocket? = null

    val port: Int
        get() = server?.localPort ?: -1

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(0))
        server = s
        pool.execute { acceptLoop(s) }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** Publica [source] y devuelve su ruta (por ejemplo "/stream/abc123/pelicula.mkv"). */
    fun register(source: StreamSource, fileName: String): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        sources[token] = source
        val name = URLEncoder.encode(fileName.ifBlank { "video" }, "UTF-8").replace("+", "%20")
        return "/stream/$token/$name"
    }

    fun unregister(path: String) {
        sources.remove(tokenOf(path))
    }

    fun localUrl(path: String): String = "http://127.0.0.1:$port$path"

    /** URL para otros equipos de la red (el Chromecast), usando la IP local del celular. */
    fun lanUrl(path: String, host: String): String = "http://$host:$port$path"

    private fun tokenOf(path: String): String =
        path.substringBefore('?').removePrefix("/stream/").substringBefore('/')

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (_: IOException) {
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { sock ->
                sock.soTimeout = READ_TIMEOUT_MS
                sock.tcpNoDelay = true
                val input = BufferedInputStream(sock.getInputStream())
                val out = BufferedOutputStream(sock.getOutputStream(), BUFFER_SIZE)

                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val requestLine = lines.first().split(' ')
                if (requestLine.size < 2) return
                val method = requestLine[0].uppercase()
                val path = requestLine[1]
                val headers = HashMap<String, String>()
                for (line in lines.drop(1)) {
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }

                if (method == "OPTIONS") {
                    writeHead(
                        out, 204, "No Content",
                        mapOf("Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS", "Content-Length" to "0"),
                    )
                    return
                }
                if (method != "GET" && method != "HEAD") {
                    writeHead(out, 405, "Method Not Allowed", mapOf("Content-Length" to "0"))
                    return
                }
                val source = sources[tokenOf(path)]
                if (source == null) {
                    writeHead(out, 404, "Not Found", mapOf("Content-Length" to "0"))
                    return
                }

                val length = source.length
                val isCancelled = { peerClosed(sock, input) }
                when (val range = parseRange(headers["range"], length)) {
                    RangeResult.Unsatisfiable -> writeHead(
                        out, 416, "Range Not Satisfiable",
                        mapOf("Content-Range" to "bytes */$length", "Content-Length" to "0"),
                    )

                    RangeResult.Full -> {
                        writeHead(
                            out, 200, "OK",
                            mapOf(
                                "Content-Type" to source.mimeType,
                                "Content-Length" to length.toString(),
                                "Accept-Ranges" to "bytes",
                            ),
                        )
                        if (method == "GET" && length > 0) sendBody(out, source, 0, length - 1, isCancelled)
                    }

                    is RangeResult.Partial -> {
                        val r = range.range
                        writeHead(
                            out, 206, "Partial Content",
                            mapOf(
                                "Content-Type" to source.mimeType,
                                "Content-Length" to r.length.toString(),
                                "Content-Range" to "bytes ${r.start}-${r.endInclusive}/$length",
                                "Accept-Ranges" to "bytes",
                            ),
                        )
                        if (method == "GET") sendBody(out, source, r.start, r.endInclusive, isCancelled)
                    }
                }
                out.flush()
            }
        } catch (_: IOException) {
            // El cliente cerró la conexión (adelantar, pausar, cambiar de stream): es lo normal.
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            // Cualquier otro error (por ejemplo, de libtorrent) corta solo esta conexión:
            // un hilo del servidor jamás debe tumbar la app.
            lastError = e
        }
    }

    /** Último error inesperado al servir un recurso (para diagnóstico). */
    @Volatile
    var lastError: Throwable? = null
        private set

    private fun sendBody(
        out: OutputStream,
        source: StreamSource,
        start: Long,
        endInclusive: Long,
        isCancelled: () -> Boolean,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var position = start
        while (position <= endInclusive) {
            val want = min(buffer.size.toLong(), endInclusive - position + 1).toInt()
            val n = source.read(position, buffer, 0, want, isCancelled)
            if (n <= 0) break
            out.write(buffer, 0, n)
            position += n
        }
    }

    /** ¿El cliente cerró su lado? Se comprueba con una lectura de 1 ms. */
    private fun peerClosed(sock: Socket, input: InputStream): Boolean = try {
        sock.soTimeout = 1
        input.read() == -1
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true
    } finally {
        runCatching { sock.soTimeout = READ_TIMEOUT_MS }
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        while (buf.size() < MAX_HEAD) {
            val b = input.read()
            if (b == -1) return null
            buf.write(b)
            matched = when {
                b == END[matched].toInt() -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == END.size) return buf.toString("ISO-8859-1")
        }
        return null
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        for ((key, value) in BASE_HEADERS + headers) sb.append(key).append(": ").append(value).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush() // las cabeceras salen ya, aunque el cuerpo tenga que esperar piezas
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_HEAD = 16 * 1024
        private val END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        private val BASE_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Headers" to "Range",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range",
            "Connection" to "close",
        )
    }
}
