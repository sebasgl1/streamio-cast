package com.sebasgl1.streamiocast.dlna

import com.sebasgl1.streamiocast.player.CastHandoff
import com.sebasgl1.streamiocast.player.CastMedia
import com.sebasgl1.streamiocast.player.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Control de un televisor DLNA (UPnP AVTransport): le dice qué URL reproducir, play, pausa,
 * adelantar, y le pregunta cada segundo por dónde va. Se llama desde el hilo principal.
 */
class DlnaHandoff(private val events: CastHandoff.Events) : RemotePlayer {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var device: DlnaDevice? = null
        private set

    private var pollJob: Job? = null
    private var loadJob: Job? = null
    private var lastPosition = 0L
    private var duration = 0L
    private var playing = false
    private var finishedSent = false

    /** El vídeo que cargamos llegó a sonar (antes de eso, "detenido" es el estado anterior). */
    private var started = false

    /** Duración que ya conocía el celular, por si el TV no la informa. */
    private var knownDuration = 0L

    override val isConnected: Boolean
        get() = device != null

    override val positionMs: Long
        get() = lastPosition

    override val supportsTrackSwitch: Boolean = false

    fun connect(target: DlnaDevice) {
        device = target
        events.onConnected(target.name)
    }

    override fun load(media: CastMedia) {
        val d = device ?: return
        pollJob?.cancel()
        loadJob?.cancel()
        finishedSent = false
        started = false
        knownDuration = media.durationMs
        duration = media.durationMs
        lastPosition = media.startAtMs
        loadJob = scope.launch {
            try {
                runCatching { soap(d, "Stop", listOf("InstanceID" to "0")) }
                val metadata = didlLite(media.title, media.url, media.videoMime, media.srtUrl, media.live, media.durationMs)
                soap(
                    d,
                    "SetAVTransportURI",
                    listOf("InstanceID" to "0", "CurrentURI" to media.url, "CurrentURIMetaData" to metadata),
                )
                soap(d, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
                if (media.startAtMs > 5_000) seekWhenPlaying(d, media.startAtMs)
                startPolling(d)
            } catch (e: IOException) {
                events.onStatus("El televisor no aceptó el vídeo: ${e.message}", playing = false, failed = true, finished = false)
            }
        }
    }

    /** Muchos televisores ignoran el "Seek" hasta que el vídeo ya está sonando. */
    private suspend fun seekWhenPlaying(d: DlnaDevice, positionMs: Long) {
        repeat(40) {
            val state = runCatching { transportState(d) }.getOrNull()
            if (state == "PLAYING") {
                runCatching { seekTo(d, positionMs) }
                return
            }
            delay(500)
        }
    }

    private fun startPolling(d: DlnaDevice) {
        pollJob = scope.launch {
            var failures = 0
            while (isActive && device == d) {
                try {
                    val position = soap(d, "GetPositionInfo", listOf("InstanceID" to "0"))
                    val state = transportState(d)
                    failures = 0
                    val stopped = state == "STOPPED" || state == "NO_MEDIA_PRESENT"
                    if (state == "PLAYING" || state == "PAUSED_PLAYBACK") started = true
                    // Al terminar, muchos TV vuelven la posición a 0:00: se conserva la última real.
                    if (!stopped) parseDlnaTime(soapValue(position, "RelTime"))?.let { lastPosition = it }
                    parseDlnaTime(soapValue(position, "TrackDuration"))?.takeIf { it > 0 }?.let { duration = it }
                    if (duration <= 0) duration = knownDuration
                    playing = state == "PLAYING"
                    events.onProgress(lastPosition, duration)
                    val nearEnd = duration > 0 && lastPosition >= duration - 30_000
                    val finished = stopped && started && nearEnd && !finishedSent
                    if (finished) finishedSent = true
                    events.onStatus(stateLabel(state, nearEnd), playing, failed = false, finished = finished)
                } catch (e: IOException) {
                    // El TV se apagó o cambió de red: tras ~15 s sin respuesta, se da por desconectado.
                    if (++failures >= 15) {
                        device = null
                        events.onDisconnected(lastPosition)
                        return@launch
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun stateLabel(state: String?, nearEnd: Boolean): String = when (state) {
        "PLAYING" -> "Reproduciendo"
        "PAUSED_PLAYBACK" -> "En pausa"
        "TRANSITIONING" -> "Cargando…"
        "STOPPED" -> if (nearEnd) "Terminó" else "Detenido"
        "NO_MEDIA_PRESENT" -> "Sin vídeo"
        else -> state.orEmpty()
    }

    private suspend fun transportState(d: DlnaDevice): String? =
        soapValue(soap(d, "GetTransportInfo", listOf("InstanceID" to "0")), "CurrentTransportState")

    private suspend fun seekTo(d: DlnaDevice, positionMs: Long) {
        soap(
            d,
            "Seek",
            listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to formatDlnaTime(positionMs)),
        )
    }

    override fun togglePlayback() {
        val d = device ?: return
        val wasPlaying = playing
        playing = !wasPlaying
        scope.launch {
            runCatching {
                if (wasPlaying) {
                    soap(d, "Pause", listOf("InstanceID" to "0"))
                } else {
                    soap(d, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
                }
            }
        }
    }

    override fun seek(positionMs: Long) {
        val d = device ?: return
        lastPosition = positionMs.coerceAtLeast(0)
        scope.launch { runCatching { seekTo(d, lastPosition) } }
    }

    // DLNA no permite cambiar estilo ni pista de subtítulos en vivo: la sesión recarga.
    override fun setSubtitleScale(scale: Float) = Unit

    override fun activateTrack(id: Long) = Unit

    override fun hideSubtitles() = Unit

    override fun stopMedia() {
        val d = device ?: return
        pollJob?.cancel()
        scope.launch { runCatching { soap(d, "Stop", listOf("InstanceID" to "0")) } }
    }

    override fun disconnect() {
        val last = lastPosition
        stopMedia()
        device = null
        events.onDisconnected(last)
    }

    /** Suelta el televisor sin avisar (cuando se pasa a un Chromecast). */
    fun forget() {
        stopMedia()
        device = null
    }

    override fun release() {
        pollJob?.cancel()
        loadJob?.cancel()
        device = null
        // Deja terminar un "Stop" pendiente antes de cerrar.
        scope.launch {
            delay(3_000)
            scope.cancel()
        }
    }

    private suspend fun soap(d: DlnaDevice, action: String, args: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(d.avTransportUrl)
                .header("SOAPAction", "\"$AV_TRANSPORT#$action\"")
                .post(soapEnvelope(action, args).toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(soapFault(body) ?: "HTTP ${response.code}")
                }
                body
            }
        }
}
