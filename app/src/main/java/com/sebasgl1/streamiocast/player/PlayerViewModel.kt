package com.sebasgl1.streamiocast.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sebasgl1.streamiocast.StremioApp
import com.sebasgl1.streamiocast.data.StreamResult
import com.sebasgl1.streamiocast.torrent.TorrentRequest
import com.sebasgl1.streamiocast.torrent.TorrentStats
import com.sebasgl1.streamiocast.torrent.TorrentStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lo que se va a reproducir: un torrent (infoHash) o un enlace directo (url). */
data class PlaybackRequest(
    val title: String,
    val infoHash: String?,
    val fileIdx: Int?,
    val url: String?,
    val filename: String?,
    val trackers: List<String>,
)

fun StreamResult.toPlayback(title: String) = PlaybackRequest(
    title = title,
    infoHash = stream.infoHash,
    fileIdx = stream.fileIdx,
    url = stream.url,
    filename = stream.behaviorHints?.filename,
    trackers = stream.sources.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") },
)

sealed interface PlayerPhase {
    data class Preparing(val message: String) : PlayerPhase
    data object Playing : PlayerPhase
    data class Failed(val message: String) : PlayerPhase
}

data class PlayerUi(
    val title: String = "",
    val phase: PlayerPhase = PlayerPhase.Preparing("Preparando…"),
    val buffering: Boolean = true,
    val stats: TorrentStats? = null,
    val isTorrent: Boolean = false,
)

@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val stremio = app as StremioApp
    private val request = stremio.pendingPlayback

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(app).setDataSourceFactory(
                // Timeouts largos: el servidor local espera a que lleguen las piezas del torrent.
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(30_000)
                    .setReadTimeoutMs(180_000)
                    .setAllowCrossProtocolRedirects(true),
            ),
        )
        .build()

    private val _ui = MutableStateFlow(
        PlayerUi(title = request?.title.orEmpty(), isTorrent = request?.infoHash != null),
    )
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private var stream: TorrentStream? = null
    private var path: String? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _ui.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
            }

            override fun onPlayerError(error: PlaybackException) {
                _ui.update { it.copy(phase = PlayerPhase.Failed(describe(error))) }
            }
        })
        start()
    }

    private fun start() {
        viewModelScope.launch {
            val req = request
            if (req == null) {
                _ui.update { it.copy(phase = PlayerPhase.Failed("Vuelve a elegir el stream.")) }
                return@launch
            }
            try {
                val url = when {
                    req.infoHash != null -> {
                        val s = stremio.torrents.open(
                            TorrentRequest(req.infoHash, req.fileIdx, req.filename, req.trackers),
                        ) { message -> _ui.update { it.copy(phase = PlayerPhase.Preparing(message)) } }
                        stream = s
                        val server = stremio.streamServer
                        withContext(Dispatchers.IO) { server.start() }
                        val p = server.register(s, s.fileName)
                        path = p
                        watchStats(s)
                        server.localUrl(p)
                    }

                    req.url != null -> req.url

                    else -> throw IllegalStateException("Este stream no trae ni torrent ni enlace.")
                }
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.playWhenReady = true
                _ui.update { it.copy(phase = PlayerPhase.Playing) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(phase = PlayerPhase.Failed(e.message ?: "No se pudo abrir el stream.")) }
            }
        }
    }

    private fun watchStats(s: TorrentStream) {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val stats = stremio.torrents.stats(s)
                _ui.update { it.copy(stats = stats) }
                delay(1000)
            }
        }
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        -> "Tu celular no puede decodificar este vídeo. Prueba con otro stream."

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        -> "La descarga va demasiado lenta o se cortó. Prueba con un stream con más seeders."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        -> "El archivo está dañado o en un formato no soportado."

        else -> "No se pudo reproducir (${error.errorCodeName})."
    }

    override fun onCleared() {
        player.release()
        path?.let { stremio.streamServer.unregister(it) }
        stream?.let { stremio.torrents.close(it) }
        super.onCleared()
    }
}
