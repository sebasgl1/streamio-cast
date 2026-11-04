package com.sebasgl1.streamiocast.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sebasgl1.streamiocast.StremioApp
import com.sebasgl1.streamiocast.data.PlaybackPrefs
import com.sebasgl1.streamiocast.data.StreamResult
import com.sebasgl1.streamiocast.data.SubtitleItem
import com.sebasgl1.streamiocast.subtitles.ExternalSubtitle
import com.sebasgl1.streamiocast.subtitles.SubCue
import com.sebasgl1.streamiocast.subtitles.languageName
import com.sebasgl1.streamiocast.subtitles.normalizeLang
import com.sebasgl1.streamiocast.torrent.TorrentRequest
import com.sebasgl1.streamiocast.torrent.TorrentStats
import com.sebasgl1.streamiocast.torrent.TorrentStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Lo que se va a reproducir: un torrent (infoHash) o un enlace directo (url). */
data class PlaybackRequest(
    val title: String,
    /** Tipo e id del contenido ("movie", "tt0133093" o "series", "tt0944947:1:1"), para los subtítulos. */
    val contentType: String,
    val contentId: String,
    val infoHash: String?,
    val fileIdx: Int?,
    val url: String?,
    val filename: String?,
    val trackers: List<String>,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val streamSubtitles: List<SubtitleItem> = emptyList(),
)

fun StreamResult.toPlayback(title: String, type: String, id: String) = PlaybackRequest(
    title = title,
    contentType = type,
    contentId = id,
    infoHash = stream.infoHash,
    fileIdx = stream.fileIdx,
    url = stream.url,
    filename = stream.behaviorHints?.filename,
    trackers = stream.sources.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") },
    videoHash = stream.behaviorHints?.videoHash,
    videoSize = stream.behaviorHints?.videoSize,
    streamSubtitles = stream.subtitles,
)

sealed interface PlayerPhase {
    data class Preparing(val message: String) : PlayerPhase
    data object Playing : PlayerPhase
    data class Failed(val message: String) : PlayerPhase
}

/** Una pista de audio o de subtítulos que viene dentro del archivo. */
data class TrackOption(
    val id: String,
    val label: String,
    /** Idioma normalizado a 2 letras. */
    val lang: String?,
    val selected: Boolean,
    val supported: Boolean,
)

data class PlayerUi(
    val title: String = "",
    val phase: PlayerPhase = PlayerPhase.Preparing("Preparando…"),
    val buffering: Boolean = true,
    val stats: TorrentStats? = null,
    val isTorrent: Boolean = false,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    val externalSubs: List<ExternalSubtitle> = emptyList(),
    val subsLoading: Boolean = false,
    /** Id del subtítulo externo activo, o null. */
    val externalSelected: String? = null,
    val subtitleOffsetMs: Long = 0,
    val subtitleSize: Int = 1,
    val subtitleError: String? = null,
)

@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val stremio = app as StremioApp
    private val prefs = stremio.playbackPrefs
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
        PlayerUi(
            title = request?.title.orEmpty(),
            isTorrent = request?.infoHash != null,
            subtitleSize = prefs.subtitleSize,
        ),
    )
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private var stream: TorrentStream? = null
    private var path: String? = null

    private var trackRefs: Map<String, Pair<TrackGroup, Int>> = emptyMap()

    @Volatile
    private var cues: List<SubCue> = emptyList()
    private var subtitleJob: Job? = null
    private var externalsLoaded = false
    private var autoSelectDone = false

    init {
        // Idiomas preferidos para las pistas que vienen dentro del archivo.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (prefs.audioLang != PlaybackPrefs.ORIGINAL) setPreferredAudioLanguage(prefs.audioLang)
            if (prefs.subtitleLang == PlaybackPrefs.OFF) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setPreferredTextLanguage(prefs.subtitleLang)
            }
        }.build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _ui.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = stremio.streamServer.lastError
                    ?.let { "\n\nDetalle: ${it.javaClass.simpleName}: ${it.message}" }
                    .orEmpty()
                _ui.update { it.copy(phase = PlayerPhase.Failed(describe(error) + detail)) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                refreshTracks(tracks)
                maybeAutoSelectSubtitles()
            }
        })
        start()
    }

    // --- Arranque -------------------------------------------------------------------

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
                loadSubtitles(req, stream)
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

    // --- Subtítulos externos (addons) ---------------------------------------------------

    private fun loadSubtitles(req: PlaybackRequest, s: TorrentStream?) {
        viewModelScope.launch {
            _ui.update { it.copy(subsLoading = true) }
            val (hash, size) = videoFingerprint(req, s)
            val subs = try {
                stremio.subtitles.list(
                    type = req.contentType,
                    id = req.contentId,
                    videoHash = hash,
                    videoSize = size,
                    filename = req.filename ?: s?.fileName,
                    streamSubtitles = req.streamSubtitles,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            externalsLoaded = true
            _ui.update { it.copy(externalSubs = subs, subsLoading = false) }
            maybeAutoSelectSubtitles()
        }
    }

    /** Hash de OpenSubtitles y tamaño: así salen primero los subtítulos sincronizados con este release. */
    private suspend fun videoFingerprint(req: PlaybackRequest, s: TorrentStream?): Pair<String?, Long?> {
        if (req.videoHash != null) return req.videoHash to req.videoSize
        val fallback: Pair<String?, Long?> = null to (s?.length ?: req.videoSize)
        val result: Pair<String?, Long?>? = withTimeoutOrNull(HASH_TIMEOUT_MS) {
            try {
                when {
                    s != null -> runInterruptible(Dispatchers.IO) { s.computeSubtitleHash() } to s.length
                    req.url != null -> stremio.subtitles.remoteFingerprint(req.url)
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        return result ?: fallback
    }

    fun selectExternal(sub: ExternalSubtitle) {
        subtitleJob?.cancel()
        subtitleJob = viewModelScope.launch {
            _ui.update { it.copy(subtitleError = null) }
            val loaded = try {
                stremio.subtitles.load(sub)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (loaded.isEmpty()) {
                _ui.update { it.copy(subtitleError = "No se pudo cargar ese subtítulo. Prueba con otro.") }
                return@launch
            }
            cues = loaded
            // Se apagan los subtítulos internos para no ver dos a la vez.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            _ui.update { it.copy(externalSelected = sub.id) }
        }
    }

    /** Texto que se debe ver ahora mismo (lo consulta la pantalla varias veces por segundo). */
    fun currentCueText(): String {
        val list = cues
        if (list.isEmpty() || _ui.value.externalSelected == null) return ""
        val t = player.currentPosition - _ui.value.subtitleOffsetMs
        return list.filter { t >= it.startMs && t < it.endMs }.joinToString("\n") { it.text }
    }

    fun shiftSubtitles(deltaMs: Long) {
        _ui.update { it.copy(subtitleOffsetMs = it.subtitleOffsetMs + deltaMs) }
    }

    fun setSubtitleSize(size: Int) {
        prefs.subtitleSize = size
        _ui.update { it.copy(subtitleSize = size) }
    }

    fun preferredSubtitleLang(): String = prefs.subtitleLang

    // --- Pistas internas (audio y subtítulos del archivo) -------------------------------

    private fun refreshTracks(tracks: Tracks) {
        val refs = HashMap<String, Pair<TrackGroup, Int>>()
        fun options(type: Int): List<TrackOption> = buildList {
            tracks.groups.forEachIndexed { g, group ->
                if (group.type != type) return@forEachIndexed
                for (i in 0 until group.length) {
                    val id = "$type-$g-$i"
                    val format = group.getTrackFormat(i)
                    refs[id] = group.mediaTrackGroup to i
                    add(
                        TrackOption(
                            id = id,
                            label = trackLabel(format, type),
                            lang = normalizeLang(format.language),
                            selected = group.isTrackSelected(i),
                            supported = group.isTrackSupported(i),
                        ),
                    )
                }
            }
        }
        val audio = options(C.TRACK_TYPE_AUDIO)
        val text = options(C.TRACK_TYPE_TEXT)
        trackRefs = refs
        _ui.update { it.copy(audioTracks = audio, textTracks = text) }
    }

    fun selectAudio(id: String) {
        val (group, index) = trackRefs[id] ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group, index))
            .build()
    }

    fun selectEmbeddedText(id: String) {
        val (group, index) = trackRefs[id] ?: return
        clearExternal()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group, index))
            .build()
    }

    fun disableSubtitles() {
        clearExternal()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun clearExternal() {
        subtitleJob?.cancel()
        cues = emptyList()
        _ui.update { it.copy(externalSelected = null, subtitleError = null) }
    }

    /**
     * Como Stremio: si el audio no está en tu idioma y el archivo no trae subtítulos en tu idioma,
     * activa el primer subtítulo externo de tu idioma (suelen venir primero los sincronizados).
     */
    private fun maybeAutoSelectSubtitles() {
        if (autoSelectDone || !externalsLoaded) return
        val state = _ui.value
        if (state.audioTracks.isEmpty() && state.textTracks.isEmpty()) return // aún no se conocen las pistas
        autoSelectDone = true

        val pref = prefs.subtitleLang
        if (pref == PlaybackPrefs.OFF) return
        if (state.textTracks.any { it.selected } || state.externalSelected != null) return
        val audioLang = state.audioTracks.firstOrNull { it.selected }?.lang
        if (audioLang == pref) return
        val sub = state.externalSubs.firstOrNull { normalizeLang(it.lang) == pref } ?: return
        selectExternal(sub)
    }

    // --- Utilidades -------------------------------------------------------------------

    private fun trackLabel(format: Format, type: Int): String {
        val name = format.label?.takeIf { it.isNotBlank() }
            ?: format.language?.let { languageName(it, it) }
            ?: "Pista sin nombre"
        val extras = buildList {
            if (type == C.TRACK_TYPE_AUDIO) {
                when (format.channelCount) {
                    1 -> add("Mono")
                    2 -> add("Estéreo")
                    6 -> add("5.1")
                    8 -> add("7.1")
                }
            }
            codecLabel(format.sampleMimeType)?.let { add(it) }
        }
        return (listOf(name) + extras).joinToString(" · ")
    }

    private fun codecLabel(mime: String?): String? = when (mime) {
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "E-AC3"
        "audio/eac3-joc" -> "E-AC3 Atmos"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd", "audio/vnd.dts.uhd" -> "DTS-HD"
        "audio/true-hd" -> "TrueHD"
        "audio/opus" -> "Opus"
        "audio/flac" -> "FLAC"
        "audio/mpeg" -> "MP3"
        "application/x-subrip" -> "SRT"
        "text/x-ssa" -> "ASS"
        "text/vtt" -> "VTT"
        "application/pgs" -> "PGS"
        "application/vobsub" -> "VobSub"
        else -> null
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

    companion object {
        private const val HASH_TIMEOUT_MS = 60_000L
    }
}
