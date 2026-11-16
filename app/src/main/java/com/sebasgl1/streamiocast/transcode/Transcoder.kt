package com.sebasgl1.streamiocast.transcode

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.sebasgl1.streamiocast.stream.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Cómo va la conversión. */
data class ConvertProgress(
    /** Bytes ya convertidos (listos para mandar al televisor). */
    val bytes: Long = 0,
    /** Velocidad respecto al tiempo real: 2.0 = convierte el doble de rápido de lo que se ve. */
    val speed: Double = 0.0,
    val finished: Boolean = false,
    /** No nulo si FFmpeg falló (motivo, en una línea). */
    val error: String? = null,
)

/**
 * Una conversión en curso con FFmpeg. Lee el vídeo del servidor local (que a su vez lo saca del
 * torrent) y escribe la versión convertida en un archivo que va creciendo; [source] deja que el
 * servidor se lo vaya entregando al televisor mientras tanto.
 */
class Transcoder(context: Context, private val plan: ConvertPlan) {

    private val dir = File(context.cacheDir, "convert").apply { mkdirs() }
    private val output = File(dir, "convertido-${System.nanoTime()}.${plan.container.extension}")

    private val _progress = MutableStateFlow(ConvertProgress())
    val progress: StateFlow<ConvertProgress> = _progress

    @Volatile
    private var session: FFmpegSession? = null

    @Volatile
    private var stopped = false

    private val lastLines = ArrayDeque<String>()

    /** El archivo convertido, para el servidor local. */
    val source: StreamSource = GrowingFile()

    fun start(input: String, startMs: Long) {
        val args = plan.ffmpegArgs(input, output.absolutePath, startMs).toTypedArray()
        session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { done ->
                val code = done.returnCode
                val failed = !stopped && !ReturnCode.isSuccess(code) && !ReturnCode.isCancel(code)
                _progress.value = _progress.value.copy(
                    bytes = output.length(),
                    finished = true,
                    error = if (failed) describeFailure() else null,
                )
            },
            { log ->
                val line = log.message?.trim().orEmpty()
                if (line.isNotEmpty()) {
                    synchronized(lastLines) {
                        lastLines.addLast(line)
                        while (lastLines.size > 8) lastLines.removeFirst()
                    }
                }
            },
            { stats ->
                _progress.value = _progress.value.copy(bytes = output.length(), speed = stats.speed)
            },
        )
    }

    private fun describeFailure(): String {
        val lines = synchronized(lastLines) { lastLines.toList() }
        val text = lines.joinToString(" ").lowercase()
        return when {
            "mediacodec" in text -> "el celular no pudo codificar el vídeo con su chip"
            "decoder" in text && "not found" in text -> "FFmpeg no trae decodificador para este formato"
            "no space" in text -> "no queda espacio en el celular"
            else -> lines.lastOrNull()?.take(160) ?: "FFmpeg se detuvo sin explicar por qué"
        }
    }

    /** Bytes ya convertidos (tamaño actual del archivo). */
    fun outputBytes(): Long = output.length()

    /** Detiene FFmpeg y borra el archivo convertido. */
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { session?.let { FFmpegKit.cancel(it.sessionId) } }
        (source as GrowingFile).close()
        // FFmpeg tarda un instante en soltar el archivo: se borra ya y también al terminar.
        runCatching { output.delete() }
        Thread {
            runCatching { Thread.sleep(3_000) }
            runCatching { output.delete() }
        }.apply { isDaemon = true }.start()
    }

    private inner class GrowingFile : StreamSource {
        /** Largo desconocido: el servidor lo sirve como transmisión en vivo. */
        override val length: Long = -1
        override val mimeType: String = plan.container.dlnaMime

        private var channel: FileChannel? = null

        override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int, isCancelled: () -> Boolean): Int {
            var waited = 0L
            while (true) {
                if (stopped) throw InterruptedIOException("Conversión detenida")
                val state = _progress.value
                val available = output.length()
                if (position < available) {
                    val ch = synchronized(this) {
                        channel ?: RandomAccessFile(output, "r").channel.also { channel = it }
                    }
                    val n = ch.read(ByteBuffer.wrap(buffer, offset, len), position)
                    if (n > 0) return n
                } else if (state.finished) {
                    return -1
                }
                if (isCancelled()) throw InterruptedIOException("Lectura cancelada")
                // FFmpeg puede estar esperando piezas del torrent: se le da tiempo, pero no eterno.
                if (waited > WAIT_LIMIT_MS) throw IOException("La conversión no avanzó a tiempo")
                Thread.sleep(120)
                waited += 120
            }
        }

        fun close() {
            synchronized(this) {
                runCatching { channel?.close() }
                channel = null
            }
        }
    }

    companion object {
        private const val WAIT_LIMIT_MS = 240_000L
    }
}

/** Borra restos de conversiones anteriores (si la app se cerró a medias). No toca FFmpeg. */
fun cleanUpConversions(context: Context) {
    runCatching { File(context.cacheDir, "convert").listFiles()?.forEach { it.delete() } }
}
