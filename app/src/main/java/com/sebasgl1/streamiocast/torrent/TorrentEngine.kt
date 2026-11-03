package com.sebasgl1.streamiocast.torrent

import android.content.Context
import com.sebasgl1.streamiocast.stream.StreamSource
import com.sebasgl1.streamiocast.stream.mimeTypeFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

data class TorrentRequest(
    val infoHash: String,
    val fileIdx: Int?,
    val filename: String?,
    val trackers: List<String>,
)

data class TorrentStats(
    val peers: Int,
    val seeds: Int,
    /** Bytes por segundo. */
    val downloadRate: Long,
    /** 0..1 del archivo elegido. */
    val progress: Float,
)

/**
 * Motor de torrents (libtorrent vía libtorrent4j). Descarga solo el archivo elegido, en orden,
 * y prioriza con "deadlines" las piezas que el reproductor está pidiendo, así adelantar funciona.
 */
class TorrentEngine(context: Context) {

    private val saveDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "torrents")
    private val session = SessionManager(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun ensureStarted() = synchronized(this) {
        if (!session.isRunning) {
            // Restos de una sesión anterior (la app se cerró a medias): fuera.
            saveDir.deleteRecursively()
            saveDir.mkdirs()
            session.start()
        }
    }

    suspend fun open(req: TorrentRequest, onProgress: (String) -> Unit): TorrentStream =
        withContext(Dispatchers.IO) {
            onProgress("Iniciando el motor de torrents…")
            ensureStarted()

            val hash = req.infoHash.lowercase()
            val sha1 = Sha1Hash.parseHex(hash)
            var returned = false
            try {
                session.download(buildMagnet(hash, req.trackers), saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                onProgress("Buscando peers…")

                var handle: TorrentHandle? = null
                val started = System.currentTimeMillis()
                while (true) {
                    ensureActive()
                    if (handle == null) handle = session.find(sha1)
                    val h = handle
                    if (h != null && h.isValid) {
                        val status = h.status()
                        if (status.hasMetadata()) break
                        onProgress("Descargando datos del torrent… ${status.numPeers()} peers")
                    }
                    if (System.currentTimeMillis() - started > METADATA_TIMEOUT_MS) {
                        throw IOException("No se encontraron peers para este torrent. Prueba con otro stream.")
                    }
                    delay(500)
                }

                val h = handle ?: throw IOException("El torrent desapareció")
                val info = h.torrentFile() ?: throw IOException("El torrent no tiene metadatos")
                val files = info.files()
                val count = files.numFiles()
                val names = (0 until count).map { files.fileName(it) }
                val sizes = (0 until count).map { files.fileSize(it) }
                val index = pickFile(names, sizes, req.fileIdx, req.filename)
                    ?: throw IOException("El torrent no tiene archivos de vídeo")

                // Solo el archivo elegido; los demás (muestras, subtítulos, .nfo) se ignoran.
                h.prioritizeFiles(Array(count) { if (it == index) Priority.TOP_PRIORITY else Priority.IGNORE })

                val span = PieceSpan(files.fileOffset(index), files.fileSize(index), info.pieceLength())
                // Principio y final primero: los reproductores leen ahí la cabecera y el índice.
                for (p in span.firstPiece..min(span.lastPiece, span.firstPiece + 1)) h.setPieceDeadline(p, 500)
                h.setPieceDeadline(span.lastPiece, 1500)

                val file = File(files.filePath(index, saveDir.absolutePath))
                onProgress("Cargando el vídeo…")
                returned = true
                TorrentStream(h, file, span, mimeTypeFor(names[index]), names[index])
            } finally {
                if (!returned) discard(sha1)
            }
        }

    fun stats(stream: TorrentStream): TorrentStats? = runCatching {
        val h = stream.handle
        if (!h.isValid) return null
        val st = h.status()
        TorrentStats(
            peers = st.numPeers(),
            seeds = st.numSeeds(),
            downloadRate = st.downloadPayloadRate().toLong(),
            progress = st.progress(),
        )
    }.getOrNull()

    /** Detiene el torrent y borra lo descargado (no queremos llenar el celular). */
    fun close(stream: TorrentStream) {
        stream.close()
        scope.launch {
            runCatching {
                if (stream.handle.isValid) session.remove(stream.handle, SessionHandle.DELETE_FILES)
            }
        }
    }

    private fun discard(sha1: Sha1Hash) {
        runCatching { session.find(sha1)?.let { session.remove(it, SessionHandle.DELETE_FILES) } }
    }

    companion object {
        private const val METADATA_TIMEOUT_MS = 90_000L
    }
}

/** El archivo del torrent visto como algo que el servidor HTTP puede servir mientras descarga. */
class TorrentStream internal constructor(
    internal val handle: TorrentHandle,
    private val file: File,
    private val span: PieceSpan,
    override val mimeType: String,
    val fileName: String,
) : StreamSource {

    override val length: Long
        get() = span.fileLength

    @Volatile
    private var closed = false

    @Volatile
    private var lastPrefetch = -1

    private var channel: FileChannel? = null

    /** Piezas a pedir por adelantado: unos 16 MB. */
    private val window = (16L * 1024 * 1024 / span.pieceLength).toInt().coerceIn(4, 48)

    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int, isCancelled: () -> Boolean): Int {
        if (position >= length) return -1
        val piece = span.pieceAt(position)
        awaitPiece(piece, isCancelled)

        val max = min(len.toLong(), span.bytesLeftInPiece(position)).toInt()
        var attempts = 0
        while (true) {
            val n = readFile(position, buffer, offset, max)
            if (n > 0) return n
            if (closed || isCancelled()) throw InterruptedIOException("Lectura cancelada")
            if (++attempts > 50) throw IOException("No se pudo leer el archivo descargado")
            Thread.sleep(100)
        }
    }

    private fun awaitPiece(piece: Int, isCancelled: () -> Boolean) {
        if (piece != lastPrefetch) {
            lastPrefetch = piece
            prefetch(piece)
        }
        if (handle.havePiece(piece)) return
        val limit = System.currentTimeMillis() + WAIT_LIMIT_MS
        while (!handle.havePiece(piece)) {
            if (closed || isCancelled()) throw InterruptedIOException("Lectura cancelada")
            if (System.currentTimeMillis() > limit) throw SocketTimeoutException("La pieza $piece no llegó a tiempo")
            Thread.sleep(150)
        }
    }

    /** Marca con urgencia creciente las piezas siguientes a la que se está leyendo. */
    private fun prefetch(from: Int) {
        val to = min(span.lastPiece, from + window - 1)
        var k = 0
        for (p in from..to) {
            if (!handle.havePiece(p)) handle.setPieceDeadline(p, 1000 + k * 250)
            k++
        }
    }

    private fun readFile(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        val ch = synchronized(this) {
            channel ?: if (file.exists()) RandomAccessFile(file, "r").channel.also { channel = it } else null
        } ?: return 0
        return ch.read(ByteBuffer.wrap(buffer, offset, len), position).coerceAtLeast(0)
    }

    fun close() {
        closed = true
        synchronized(this) {
            runCatching { channel?.close() }
            channel = null
        }
    }

    companion object {
        private const val WAIT_LIMIT_MS = 180_000L
    }
}
