package com.sebasgl1.streamiocast.player

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sebasgl1.streamiocast.StreamingService
import com.sebasgl1.streamiocast.StremioApp
import com.sebasgl1.streamiocast.cast.lanAddress
import com.sebasgl1.streamiocast.data.PlaybackPrefs
import com.sebasgl1.streamiocast.data.ProgressInput
import com.sebasgl1.streamiocast.data.StreamFilter
import com.sebasgl1.streamiocast.data.streamKeyOf
import com.sebasgl1.streamiocast.data.AudioCodec
import com.sebasgl1.streamiocast.data.audioCodecOfMime
import com.sebasgl1.streamiocast.data.classify
import com.sebasgl1.streamiocast.data.classifyTv
import com.sebasgl1.streamiocast.cast.TargetDevice
import com.sebasgl1.streamiocast.dlna.DlnaDevice
import com.sebasgl1.streamiocast.dlna.DlnaHandoff
import com.sebasgl1.streamiocast.dlna.dlnaMime
import com.sebasgl1.streamiocast.history.SavedSubtitle
import com.sebasgl1.streamiocast.history.WatchEntry
import com.sebasgl1.streamiocast.history.WatchTarget
import com.sebasgl1.streamiocast.history.isWatched
import com.sebasgl1.streamiocast.history.resumePosition
import com.sebasgl1.streamiocast.stream.LazySource
import com.sebasgl1.streamiocast.subtitles.ExternalSubtitle
import com.sebasgl1.streamiocast.subtitles.SubCue
import com.sebasgl1.streamiocast.subtitles.languageName
import com.sebasgl1.streamiocast.subtitles.normalizeLang
import com.sebasgl1.streamiocast.subtitles.toSrt
import com.sebasgl1.streamiocast.subtitles.toWebVtt
import com.sebasgl1.streamiocast.torrent.TorrentRequest
import com.sebasgl1.streamiocast.torrent.formatBytes
import com.sebasgl1.streamiocast.transcode.AudioPick
import com.sebasgl1.streamiocast.transcode.ConvertPlan
import com.sebasgl1.streamiocast.transcode.Transcoder
import com.sebasgl1.streamiocast.transcode.VideoTarget
import com.sebasgl1.streamiocast.transcode.planForCast
import com.sebasgl1.streamiocast.transcode.planForTv
import java.io.File
import com.sebasgl1.streamiocast.torrent.TorrentStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * Una reproducción en curso: torrent, servidor local, reproductor, subtítulos y Chromecast.
 * Vive en la app (no en la pantalla): sobrevive a que Android recree la pantalla y, si se está
 * transmitiendo, sigue aunque vuelvas al resto de la app. Todos sus métodos van en el hilo principal.
 */
@OptIn(UnstableApi::class)
class PlaybackSession(private val app: StremioApp, val request: PlaybackRequest) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.playbackPrefs
    private val history = app.history
    private val target = request.target

    /** Lo guardado de este vídeo; si es un episodio nuevo, lo último de la serie (solo idiomas). */
    private val savedEntry: WatchEntry? = history.get(target.videoId)
    private val seriesEntry: WatchEntry? =
        if (savedEntry == null) history.lastForMeta(target.metaId) else null

    var isOpen = true
        private set

    /** La pantalla del reproductor está a la vista. */
    var attached = false

    // Arranque con buffer: cuánto vídeo descargado esperar antes de reproducir.
    private val startMode = prefs.startMode
    private val bufferTargetMs = prefs.bufferSeconds.coerceIn(5, 120) * 1000L

    /** Abierta = ya se puede reproducir. En modo automático lo está desde el principio. */
    private var gateOpen = startMode == PlaybackPrefs.START_AUTO
    private var waitJob: Job? = null

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setLoadControl(buildLoadControl())
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
            title = request.title,
            isTorrent = request.infoHash != null,
            subtitleSize = prefs.subtitleSize,
            rebufferSec = if (startMode == PlaybackPrefs.START_AUTO) null else (bufferTargetMs / 1000).toInt(),
        ),
    )
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    val isCasting: Boolean
        get() = remote != null

    private var stream: TorrentStream? = null
    private var path: String? = null

    private var trackRefs: Map<String, Pair<TrackGroup, Int>> = emptyMap()

    @Volatile
    private var cues: List<SubCue> = emptyList()
    private var subtitleJob: Job? = null
    private var externalsLoaded = false
    private var autoSelectDone = false
    private var audioApplied = false

    // Siguiente episodio
    private var nextTarget: WatchTarget? = null
    private var countdownJob: Job? = null

    // --- Chromecast ---------------------------------------------------------------------

    private var castJob: Job? = null
    private var shiftJob: Job? = null
    private var castSubtitlePaths: List<String> = emptyList()

    /** Retraso → id de la pista ya cargada en el televisor con ese retraso. */
    private var castOffsetTracks: Map<Long, Long> = emptyMap()

    private val remoteEvents: CastHandoff.Events = object : CastHandoff.Events {
        override fun onConnected(device: String) {
            // Si se pasa de un aparato a otro (TV → Chromecast), se sigue donde iba el anterior,
            // no donde se quedó el celular cuando empezó a transmitir.
            val at = if (remoteLoaded) _ui.value.cast?.positionMs ?: player.currentPosition else player.currentPosition
            // Aparato nuevo: lo que se convertía para el anterior ya no aplica.
            dropConversion()
            remoteLoaded = false
            _ui.update {
                it.copy(cast = CastUi(device), castMessage = null, castBlocked = null, convertOffer = null, compatOk = null)
            }
            // Si aún se está llenando el buffer, se manda al TV cuando termine (o le des reproducir).
            if (_ui.value.phase is PlayerPhase.Playing && gateOpen) sendToCast(at)
        }

        override fun onDisconnected(lastPositionMs: Long) {
            // Se pasó de un aparato a otro (por ejemplo, del Chromecast al TV): nada que hacer.
            if (remote != null) return
            val lastPositionMs = lastPositionMs + convertShift()
            dropConversion()
            remoteLoaded = false
            app.dlnaDevice = null
            saveProgress()
            castJob?.cancel()
            clearCastSubtitles()
            _ui.update { it.copy(cast = null, castMessage = null, castBlocked = null, convertOffer = null, converting = null) }
            if (!attached) {
                // Nadie está mirando el celular: se termina la reproducción.
                closeLater()
                return
            }
            if (isOpen && _ui.value.phase is PlayerPhase.Playing) {
                // Vuelve al celular donde quedó el televisor (en pausa).
                if (lastPositionMs > 0) player.seekTo(lastPositionMs)
                player.playWhenReady = false
                player.prepare()
            }
        }

        override fun onStatus(state: String, playing: Boolean, failed: Boolean, finished: Boolean) {
            _ui.update { s ->
                s.copy(
                    cast = s.cast?.copy(state = state, playing = playing),
                    castMessage = if (failed) (if (remote === dlna) DLNA_FAILED else CAST_FAILED) else s.castMessage,
                )
            }
            if (finished) onEnded()
        }

        override fun onProgress(positionMs: Long, durationMs: Long) {
            // Convertido: el aparato cuenta desde donde empezó la conversión y no sabe la duración.
            val converting = convertPlan != null
            val position = pendingSeekMs ?: (positionMs + convertShift())
            val duration = if (converting) lastKnownDuration.takeIf { it > 0 } ?: (durationMs + convertShift()) else durationMs
            _ui.update { s -> s.copy(cast = s.cast?.copy(positionMs = position, durationMs = duration)) }
        }

        override fun onConnectFailed() {
            _ui.update { it.copy(castMessage = "No se pudo conectar con el Chromecast.") }
        }
    }

    private val cast = CastHandoff(app, remoteEvents)
    private val dlna = DlnaHandoff(remoteEvents)

    /** El reproductor remoto activo: un Chromecast o un televisor DLNA. */
    private val remote: RemotePlayer?
        get() = when {
            // El TV DLNA solo se conecta a propósito y suelta el Chromecast (que tarda en soltarse).
            dlna.isConnected -> dlna
            cast.isConnected -> cast
            else -> null
        }

    // Revisión del formato real (para el Chromecast).
    private var lastTracks: Tracks? = null
    private val tracksKnown = CompletableDeferred<Unit>()

    // Sincronización con la cuenta de Stremio.
    private var lastPush = 0L

    // --- Conversión en el celular (FFmpeg) ------------------------------------------------
    /** El plan que la persona aceptó; mientras no sea null, al TV le llega la versión convertida. */
    private var convertPlan: ConvertPlan? = null

    /** El plan ofrecido, a la espera de que toque "Convertir en el celular". */
    private var offeredPlan: ConvertPlan? = null
    private var transcoder: Transcoder? = null
    private var convertPath: String? = null

    /** Desde qué punto del vídeo empieza la versión convertida (el TV cuenta desde 0). */
    private var convertBaseMs = 0L
    private var convertWatch: Job? = null
    private var convertSeekJob: Job? = null
    private var pendingSeekMs: Long? = null

    /** El aparato ya tiene cargado nuestro vídeo (para saber de dónde tomar la posición). */
    private var remoteLoaded = false
    private var lastKnownDuration = 0L

    private fun convertShift(): Long = if (convertPlan != null) convertBaseMs else 0L

    /** Posición real en el vídeo de lo que suena en el aparato. */
    private fun remotePos(): Long {
        pendingSeekMs?.let { return it }
        // Aún no hay nada cargado en el aparato (revisando o "No compatible"): manda el celular.
        if (!remoteLoaded) return player.currentPosition
        return (remote?.positionMs ?: 0L) + convertShift()
    }

    /** La pista de audio que elegiste en la app (posición entre las pistas de audio), si elegiste una. */
    private var userAudioIndex: Int? = null

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
                if (playbackState == Player.STATE_ENDED && remote == null) onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Le diste reproducir en los controles del vídeo antes de que se llenara el buffer.
                if (isPlaying && !gateOpen) beginPlayback()
                _ui.update { it.copy(localPlaying = isPlaying) }
                if (!isPlaying) {
                    saveProgress()
                    pushSync()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = app.streamServer.lastError
                    ?.let { "\n\nDetalle: ${it.javaClass.simpleName}: ${it.message}" }
                    .orEmpty()
                _ui.update { it.copy(phase = PlayerPhase.Failed(describe(error) + detail)) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (!tracks.groups.isEmpty()) {
                    lastTracks = tracks
                    tracksKnown.complete(Unit)
                }
                refreshTracks(tracks)
                applySavedAudio()
                maybeAutoSelectSubtitles()
            }
        })
        start()
        trackProgress()
        loadNextEpisode()
    }

    // --- Arranque -------------------------------------------------------------------

    private fun start() {
        scope.launch {
            // Notificación con controles; además mantiene viva la descarga con la pantalla apagada.
            StreamingService.start(app, request.title)
            try {
                val url = when {
                    request.infoHash != null -> {
                        val s = app.torrents.open(
                            TorrentRequest(request.infoHash, request.fileIdx, request.filename, request.trackers),
                        ) { message -> _ui.update { it.copy(phase = PlayerPhase.Preparing(message)) } }
                        if (!isOpen) {
                            app.torrents.close(s)
                            return@launch
                        }
                        stream = s
                        val server = app.streamServer
                        withContext(Dispatchers.IO) { server.start() }
                        val p = server.register(s, s.fileName)
                        path = p
                        watchStats(s)
                        server.localUrl(p)
                    }

                    request.url != null -> request.url

                    else -> throw IllegalStateException("Este stream no trae ni torrent ni enlace.")
                }

                // Retoma donde ibas. Si hay un Chromecast conectado, el celular no suena: va al TV.
                val resumeAt = resumePosition(savedEntry) ?: 0L
                player.setMediaItem(MediaItem.fromUri(url), resumeAt)
                player.prepare()
                // En pausa mientras se llena el buffer: el reproductor sigue descargando por delante.
                player.playWhenReady = gateOpen && !(cast.hasConnectedDevice() || app.dlnaDevice != null)
                _ui.update { it.copy(phase = PlayerPhase.Playing) }
                if (resumeAt > 0) showResumeNotice(resumeAt)
                if (!gateOpen) waitForBuffer()

                if (remote != null) {
                    if (gateOpen) sendToCast(resumeAt)
                } else {
                    cast.adoptExistingSession()
                    // El televisor DLNA que venías usando (por ejemplo, en el episodio anterior).
                    if (!cast.isConnected) app.dlnaDevice?.let { dlna.connect(it) }
                }
                loadSubtitles(stream)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(phase = PlayerPhase.Failed(e.message ?: "No se pudo abrir el stream.")) }
            }
        }
    }

    /**
     * Rebuffer: si a mitad del vídeo se acaba lo descargado, ExoPlayer espera a tener otra vez los
     * mismos segundos antes de seguir (en vez de trabarse cada poco). Guarda un margen por encima.
     */
    private fun buildLoadControl(): DefaultLoadControl {
        if (startMode == PlaybackPrefs.START_AUTO) return DefaultLoadControl()
        val keep = maxOf(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS.toLong(), bufferTargetMs + 15_000).toInt()
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                keep,
                keep,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                bufferTargetMs.toInt(),
            )
            .build()
    }

    /** Mide los segundos de vídeo ya descargados por delante y arranca al llegar a la meta. */
    private fun waitForBuffer() {
        waitJob?.cancel()
        waitJob = scope.launch {
            // Al encadenar episodios no se espera tu toque: nadie tiene por qué estar mirando.
            val manual = startMode == PlaybackPrefs.START_MANUAL && !request.autoNext
            var last = 0L
            var rate = 0.0 // ms de vídeo descargados por segundo
            while (isActive && !gateOpen) {
                val buffered = player.totalBufferedDuration
                val duration = player.duration
                val toEnd = duration != C.TIME_UNSET && duration > 0 && player.bufferedPosition >= duration - 1_000
                // Memoria del reproductor llena (vídeos muy pesados): ya no cabe más por delante.
                val full = player.playbackState == Player.STATE_READY && !player.isLoading && buffered > 0
                val perSecond = (buffered - last).coerceAtLeast(0) * 2.0
                rate = if (rate == 0.0) perSecond else rate * 0.7 + perSecond * 0.3
                last = buffered
                val ready = buffered >= bufferTargetMs || toEnd || full
                val eta = if (!ready && rate > 200) ((bufferTargetMs - buffered) / rate).toInt().coerceAtLeast(1) else null
                _ui.update {
                    it.copy(startWait = StartWait(buffered.coerceAtMost(bufferTargetMs), bufferTargetMs, manual, ready, eta))
                }
                if (ready && !manual) {
                    beginPlayback()
                    return@launch
                }
                delay(500)
            }
        }
    }

    /** Quita el aviso amarillo (se toca, o se va solo a los segundos). */
    fun dismissMessage(text: String) {
        _ui.update { if (it.castMessage == text) it.copy(castMessage = null) else it }
    }

    /** "Reproducir ya": arranca aunque no se haya llenado el buffer. */
    fun startNow() {
        if (!gateOpen) beginPlayback()
    }

    private fun beginPlayback() {
        if (gateOpen) return
        gateOpen = true
        waitJob?.cancel()
        _ui.update { it.copy(startWait = null) }
        if (remote != null) sendToCast(player.currentPosition) else player.play()
    }

    private fun showResumeNotice(atMs: Long) {
        _ui.update { it.copy(resumedFromMs = atMs) }
        scope.launch {
            delay(10_000)
            _ui.update { it.copy(resumedFromMs = null) }
        }
    }

    fun restartFromBeginning() {
        if (remote != null) castSeek(0) else player.seekTo(0)
        _ui.update { it.copy(resumedFromMs = null) }
    }

    private fun watchStats(s: TorrentStream) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val stats = app.torrents.stats(s)
                _ui.update { it.copy(stats = stats) }
                delay(1000)
            }
        }
    }

    // --- Controles (pantalla y notificación) --------------------------------------------

    fun togglePlay() {
        if (!gateOpen) {
            beginPlayback()
            return
        }
        val r = remote
        when {
            r != null -> r.togglePlayback()
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        if (remote != null) {
            castSkip(deltaMs)
        } else {
            player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))
        }
    }

    // --- Seguimiento (dónde vas) ----------------------------------------------------

    private fun trackProgress() {
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(5_000)
                saveProgress()
                if (++ticks % 24 == 0) pushSync() // cada 2 minutos
            }
        }
    }

    /** Sube a tu cuenta de Stremio dónde vas (para verlo igual en la app oficial). */
    private fun pushSync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPush < 20_000) return
        val entry = history.get(target.videoId) ?: return
        if (entry.positionMs < 5_000 && !entry.watched) return
        lastPush = now
        app.sync.push(
            ProgressInput(
                metaId = target.metaId,
                type = target.type,
                name = target.name,
                poster = target.poster,
                videoId = target.videoId,
                positionMs = entry.positionMs,
                durationMs = entry.durationMs,
                watched = entry.watched,
                nextVideoId = nextTarget?.videoId,
            ),
        )
    }

    private fun saveProgress() {
        if (!isOpen || _ui.value.phase !is PlayerPhase.Playing) return
        val castUi = _ui.value.cast
        val position: Long
        val duration: Long
        if (castUi != null) {
            position = castUi.positionMs
            duration = castUi.durationMs
        } else {
            position = player.currentPosition
            duration = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        }
        if (position < 5_000) return
        history.update(target) {
            it.copy(
                positionMs = position,
                durationMs = if (duration > 0) duration else it.durationMs,
                watched = it.watched || isWatched(position, duration),
                hidden = false,
            )
        }
        // Terminaste el episodio: el siguiente aparece en "Continuar viendo", como en Stremio.
        if (isWatched(position, duration)) {
            nextTarget?.let { next ->
                if (history.get(next.videoId) == null) history.update(next) { it.copy(upNext = true) }
            }
        }
    }

    // --- Siguiente episodio ---------------------------------------------------------

    private fun loadNextEpisode() {
        if (target.season == null) return
        scope.launch {
            val meta = try {
                app.repo.fetchMeta(target.type, target.metaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@launch
            val next = nextEpisodeVideo(meta.videos, target.videoId) ?: return@launch
            nextTarget = target.copy(
                videoId = next.id,
                season = next.season,
                episode = next.episode,
                episodeTitle = next.label,
            )
            _ui.update { it.copy(nextUp = "T${next.season} E${next.episode} · ${next.label}") }
        }
    }

    /** Falta menos de un minuto: momento de ofrecer el siguiente episodio. */
    fun nearEnd(): Boolean {
        if (nextTarget == null) return false
        val c = _ui.value.cast
        val position = c?.positionMs ?: player.currentPosition
        val duration = c?.durationMs ?: player.duration
        return duration > 0 && duration - position <= 60_000
    }

    /** Terminó el vídeo (en el celular o en el TV): cuenta regresiva para el siguiente. */
    private fun onEnded() {
        saveProgress()
        pushSync(force = true)
        if (nextTarget != null) {
            if (countdownJob == null) startCountdown()
        } else if (!attached) {
            closeLater()
        }
    }

    private fun startCountdown() {
        countdownJob = scope.launch {
            for (s in 10 downTo 1) {
                _ui.update { it.copy(nextCountdown = s) }
                delay(1000)
            }
            _ui.update { it.copy(nextCountdown = null) }
            countdownJob = null
            playNext()
        }
    }

    fun cancelNext() {
        countdownJob?.cancel()
        countdownJob = null
        _ui.update { it.copy(nextCountdown = null) }
    }

    /** Busca streams del siguiente episodio, elige uno como Stremio y lo reproduce (en el TV si se transmite). */
    fun playNext() {
        val next = nextTarget ?: return
        countdownJob?.cancel()
        countdownJob = null
        scope.launch {
            _ui.update { it.copy(nextLoading = true, nextCountdown = null) }
            val outcome = try {
                app.repo.fetchStreams(next.type, next.videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val device = when {
                dlna.isConnected -> StreamFilter.TV
                cast.isConnected -> StreamFilter.CHROMECAST
                else -> StreamFilter.ALL
            }
            // Se clasifica con el perfil del aparato que se está usando (y lo que aprendió).
            val results = outcome?.results?.map { r ->
                when (device) {
                    StreamFilter.TV -> r.copy(tv = classifyTv(r.info, tvProfile()))
                    StreamFilter.CHROMECAST -> r.copy(cls = classify(r.info, castProfile()))
                    StreamFilter.ALL -> r
                }
            }
            val pick = results?.let { pickNextStream(it, request.bingeGroup, device) }
            if (pick == null) {
                _ui.update {
                    it.copy(
                        nextLoading = false,
                        castMessage = "No encontré un stream para el siguiente episodio. Elígelo desde la ficha.",
                    )
                }
                return@launch
            }
            saveProgress()
            app.startNext(pick.toPlayback(next).copy(autoNext = true, autoConvert = convertPlan != null))
        }
    }

    // --- Subtítulos externos (addons) -------------------------------------------------

    private fun loadSubtitles(s: TorrentStream?) {
        scope.launch {
            _ui.update { it.copy(subsLoading = true) }
            val (hash, size) = videoFingerprint(s)
            val subs = try {
                app.subtitles.list(
                    type = request.contentType,
                    id = request.contentId,
                    videoHash = hash,
                    videoSize = size,
                    filename = request.filename ?: s?.fileName,
                    streamSubtitles = request.streamSubtitles,
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
    private suspend fun videoFingerprint(s: TorrentStream?): Pair<String?, Long?> {
        if (request.videoHash != null) return request.videoHash to request.videoSize
        val fallback: Pair<String?, Long?> = null to (s?.length ?: request.videoSize)
        val result: Pair<String?, Long?>? = withTimeoutOrNull(HASH_TIMEOUT_MS) {
            try {
                when {
                    s != null -> runInterruptible(Dispatchers.IO) { s.computeSubtitleHash() } to s.length
                    request.url != null -> app.subtitles.remoteFingerprint(request.url)
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

    fun selectExternal(sub: ExternalSubtitle) = selectExternal(sub, record = true)

    private fun selectExternal(sub: ExternalSubtitle, record: Boolean) {
        subtitleJob?.cancel()
        subtitleJob = scope.launch {
            _ui.update { it.copy(subtitleError = null) }
            val loaded = try {
                app.subtitles.load(sub)
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
            if (record) {
                history.update(target) {
                    it.copy(
                        subtitle = SavedSubtitle(
                            kind = SavedSubtitle.EXTERNAL,
                            lang = normalizeLang(sub.lang),
                            url = sub.url,
                            offsetMs = _ui.value.subtitleOffsetMs,
                        ),
                    )
                }
            }
            // En el televisor los subtítulos van dentro de la carga: se recarga donde iba.
            if (remote != null) sendToCast(remotePos())
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
        val offset = _ui.value.subtitleOffsetMs
        history.update(target) { e -> e.copy(subtitle = e.subtitle?.copy(offsetMs = offset)) }
        val r = remote
        if (r != null && _ui.value.externalSelected != null) {
            val trackId = castOffsetTracks[offset]
            if (trackId != null && r.supportsTrackSwitch) {
                // El TV ya tiene esa versión del subtítulo: se cambia de pista, sin recargar el vídeo.
                r.activateTrack(trackId)
            } else {
                // Fuera del rango precargado (±10 s), o TV DLNA: se recarga cuando dejes de tocar el botón.
                shiftJob?.cancel()
                shiftJob = scope.launch {
                    delay(1200)
                    sendToCast(remotePos())
                }
            }
        }
    }

    fun setSubtitleSize(size: Int) {
        prefs.subtitleSize = size
        _ui.update { it.copy(subtitleSize = size) }
        remote?.setSubtitleScale(CAST_SCALE[size.coerceIn(0, 2)])
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

    fun selectAudio(id: String) = selectAudio(id, record = true)

    private fun selectAudio(id: String, record: Boolean) {
        val (group, index) = trackRefs[id] ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group, index))
            .build()
        if (record) {
            val lang = _ui.value.audioTracks.firstOrNull { it.id == id }?.lang
            history.update(target) { it.copy(audioLang = lang) }
            userAudioIndex = _ui.value.audioTracks.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            // Las pistas en pantalla no se actualizan con el celular detenido: se marca a mano.
            _ui.update { s -> s.copy(audioTracks = s.audioTracks.map { it.copy(selected = it.id == id) }) }
            if (remote != null) audioChangedWhileCasting()
        }
    }

    /**
     * Cambiaste el audio mientras transmitías. El aparato usa por su cuenta la primera pista del
     * archivo, así que para oír otra hay que mandarla desde el celular (o reconvertir).
     */
    private fun audioChangedWhileCasting() {
        val toTv = remote === dlna
        val plan = buildConvertPlan(toTv)
        when {
            convertPlan != null -> {
                // Ya se convertía: se rehace con la pista nueva (o se deja de convertir si ya no hace falta).
                val at = remotePos()
                convertPlan = plan
                if (plan == null) _ui.update { it.copy(converting = null) }
                sendToCast(at)
            }
            plan != null && remoteLoaded -> {
                offeredPlan = plan
                val device = app.selectedDevice.value?.name ?: "el televisor"
                _ui.update {
                    it.copy(
                        castBlocked = "$device reproduce por su cuenta la primera pista de audio del archivo. " +
                            "Para oír la que elegiste, hay que mandarla desde el celular.",
                        convertOffer = offerFor(plan, remotePos()),
                    )
                }
            }
        }
    }

    fun selectEmbeddedText(id: String) = selectEmbeddedText(id, record = true)

    private fun selectEmbeddedText(id: String, record: Boolean) {
        val (group, index) = trackRefs[id] ?: return
        clearExternal()
        remote?.let { r ->
            r.hideSubtitles()
            if (r === dlna) sendToCast(remotePos())
            _ui.update {
                it.copy(castMessage = "Los subtítulos internos del archivo no se pueden transmitir. Elige uno externo.")
            }
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group, index))
            .build()
        if (record) {
            val lang = _ui.value.textTracks.firstOrNull { it.id == id }?.lang
            history.update(target) { it.copy(subtitle = SavedSubtitle(SavedSubtitle.EMBEDDED, lang)) }
        }
    }

    fun disableSubtitles() = disableSubtitles(record = true)

    private fun disableSubtitles(record: Boolean) {
        clearExternal()
        remote?.let { r ->
            r.hideSubtitles()
            if (r === dlna) sendToCast(remotePos()) // el TV DLNA solo los quita recargando
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        if (record) history.update(target) { it.copy(subtitle = SavedSubtitle(SavedSubtitle.OFF)) }
    }

    private fun clearExternal() {
        subtitleJob?.cancel()
        cues = emptyList()
        _ui.update { it.copy(externalSelected = null, subtitleError = null) }
    }

    /** El audio que elegiste la última vez en este vídeo (o en esta serie). */
    private fun applySavedAudio() {
        if (audioApplied) return
        val tracks = _ui.value.audioTracks
        if (tracks.isEmpty()) return
        audioApplied = true
        val wanted = (savedEntry ?: seriesEntry)?.audioLang ?: return
        if (tracks.firstOrNull { it.selected }?.lang == wanted) return
        tracks.firstOrNull { it.lang == wanted }?.let { selectAudio(it.id, record = false) }
    }

    /**
     * Primero lo que elegiste antes en este vídeo (o el idioma que usaste en la serie). Si no hay
     * nada guardado, como Stremio: si el audio no está en tu idioma y el archivo no trae subtítulos
     * en tu idioma, activa el primer subtítulo externo de tu idioma.
     */
    private fun maybeAutoSelectSubtitles() {
        if (autoSelectDone || !externalsLoaded) return
        val state = _ui.value
        // Aún no se conocen las pistas (salvo que se esté transmitiendo, donde el celular no reproduce).
        if (state.audioTracks.isEmpty() && state.textTracks.isEmpty() && remote == null) return
        autoSelectDone = true

        val saved = savedEntry?.subtitle ?: seriesEntry?.subtitle?.copy(url = null, offsetMs = 0)
        if (saved != null) {
            applySavedSubtitle(saved, state)
            return
        }

        val pref = prefs.subtitleLang
        if (pref == PlaybackPrefs.OFF) return
        if (state.textTracks.any { it.selected } || state.externalSelected != null) return
        val audioLang = state.audioTracks.firstOrNull { it.selected }?.lang
        if (audioLang == pref) return
        val sub = state.externalSubs.firstOrNull { normalizeLang(it.lang) == pref } ?: return
        selectExternal(sub, record = false)
    }

    private fun applySavedSubtitle(saved: SavedSubtitle, state: PlayerUi) {
        when (saved.kind) {
            SavedSubtitle.OFF -> disableSubtitles(record = false)

            SavedSubtitle.EMBEDDED -> {
                val track = state.textTracks.firstOrNull { it.lang == saved.lang }
                if (track != null) {
                    selectEmbeddedText(track.id, record = false)
                } else {
                    state.externalSubs.firstOrNull { normalizeLang(it.lang) == saved.lang }
                        ?.let { selectExternal(it, record = false) }
                }
            }

            SavedSubtitle.EXTERNAL -> {
                val exact = state.externalSubs.firstOrNull { it.url == saved.url }
                val sub = exact ?: state.externalSubs.firstOrNull { normalizeLang(it.lang) == saved.lang }
                if (sub != null) {
                    // El retraso solo vale para el mismo subtítulo exacto.
                    if (exact != null) _ui.update { it.copy(subtitleOffsetMs = saved.offsetMs) }
                    selectExternal(sub, record = false)
                }
            }
        }
    }

    // --- Control del Chromecast ---------------------------------------------------------

    fun castTogglePlay() {
        remote?.togglePlayback()
    }

    fun castSeek(positionMs: Long) {
        val r = remote ?: return
        if (convertPlan == null) {
            r.seek(positionMs)
            return
        }
        // Convertido: adelantar es volver a convertir desde ese punto. Se espera a que dejes de
        // tocar para no relanzar FFmpeg con cada toque.
        val target = positionMs.coerceAtLeast(0)
        pendingSeekMs = target
        _ui.update { s -> s.copy(cast = s.cast?.copy(positionMs = target, state = "Saltando…")) }
        convertSeekJob?.cancel()
        convertSeekJob = scope.launch {
            delay(800)
            sendToCast(target)
        }
    }

    fun castSkip(deltaMs: Long) {
        if (remote == null) return
        val c = _ui.value.cast ?: return
        val max = if (c.durationMs > 0) c.durationMs else Long.MAX_VALUE
        castSeek((remotePos() + deltaMs).coerceIn(0, max))
    }

    fun stopCasting() {
        val r = remote ?: return
        if (r === dlna) app.dlnaDevice = null
        r.disconnect()
    }

    /** Transmitir a un televisor DLNA (por ejemplo, un Samsung sin Chromecast). */
    fun connectDlna(device: DlnaDevice) {
        if (dlna.isConnected) dlna.forget()
        app.dlnaDevice = device
        dlna.connect(device)
        // Si había un Chromecast, se suelta (su aviso de desconexión se ignora: ya hay otro aparato).
        if (cast.isConnected) cast.disconnect()
    }

    /** Se eligió un Chromecast: si se estaba usando un TV DLNA, se suelta sin cortar la sesión. */
    fun prepareForChromecast() {
        if (dlna.isConnected) {
            dlna.forget()
            app.dlnaDevice = null
        }
    }

    /** El vídeo no pasó la revisión de formato, pero quieres intentarlo igual. */
    fun forceCast() {
        dropConversion()
        _ui.update { it.copy(castBlocked = null, convertOffer = null) }
        sendToCast(castStartPosition(), force = true)
    }

    /** "Convertir en el celular": se acepta el plan ofrecido y se manda la versión convertida. */
    fun convertAndCast() {
        val plan = offeredPlan ?: return
        val at = castStartPosition()
        convertPlan = plan
        _ui.update { it.copy(castBlocked = null, convertOffer = null, castMessage = null) }
        sendToCast(at)
    }

    /** Dónde arrancar en el aparato: donde va él si ya suena, o donde va el celular. */
    private fun castStartPosition(): Long = remotePos()

    private fun stopTranscoder() {
        convertWatch?.cancel()
        convertWatch = null
        transcoder?.stop()
        transcoder = null
        convertPath?.let { app.streamServer.unregister(it) }
        convertPath = null
    }

    /** Se deja de convertir (cambio de aparato, "intentar igual", desconexión o cierre). */
    private fun dropConversion() {
        convertSeekJob?.cancel()
        pendingSeekMs = null
        stopTranscoder()
        convertPlan = null
        offeredPlan = null
        convertBaseMs = 0
        _ui.update { it.copy(converting = null) }
    }

    private fun videoFacts(tracks: Tracks): VideoFacts? {
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return null
        val f = group.getTrackFormat((0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0)
        return VideoFacts(f.sampleMimeType, f.width, f.height, f.codecs, f.frameRate)
    }

    /** Qué habría que convertir para el aparato actual, o null si no hay arreglo (o nada que hacer). */
    private fun buildConvertPlan(toTv: Boolean): ConvertPlan? {
        val tracks = lastTracks ?: return null
        val formats = ArrayList<Format>()
        var selected: Int? = null
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) selected = formats.size
                formats += group.getTrackFormat(i)
            }
        }
        // Mientras se transmite el celular está detenido y no refleja lo que eliges: manda lo elegido.
        userAudioIndex?.takeIf { it in formats.indices }?.let { selected = it }
        val default = formats.indexOfFirst { it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }.coerceAtLeast(0)
        val picks = formats.mapIndexed { i, f ->
            AudioPick(
                index = i,
                mime = f.sampleMimeType,
                language = f.language?.let { code -> languageName(code, code) },
                isDeviceDefault = i == default,
            )
        }
        val video = videoFacts(tracks)
        return if (toTv) planForTv(video, picks, selected, tvProfile()) else planForCast(video, picks, selected, castProfile())
    }

    /** El plan en palabras, con lo que cuesta y un aviso si puede faltar espacio. */
    private fun offerFor(plan: ConvertPlan, startAtMs: Long): ConvertOffer {
        val device = app.selectedDevice.value?.name ?: "el televisor"
        val total = stream?.length ?: request.videoSize ?: 0L
        val duration = knownDurationMs()
        val remaining = if (duration > 0) (total * (1.0 - startAtMs.toDouble() / duration)).toLong() else total
        val free = runCatching { File(app.cacheDir.absolutePath).usableSpace }.getOrDefault(Long.MAX_VALUE)
        val space = if (remaining > 0 && free < remaining + 300L * 1024 * 1024) {
            " Ojo: quedan ${formatBytes(free)} libres y la conversión puede necesitar unos ${formatBytes(remaining)}."
        } else {
            ""
        }
        return ConvertOffer(what = plan.describe(device), cost = plan.cost() + space)
    }

    /**
     * Lanza FFmpeg desde [startAtMs] y espera a que haya algo convertido. Devuelve la ruta en el
     * servidor local, o null si falló (y deja el aviso en pantalla).
     */
    private suspend fun startConversion(plan: ConvertPlan, input: String, startAtMs: Long): String? {
        stopTranscoder()
        convertBaseMs = startAtMs
        _ui.update {
            it.copy(
                cast = it.cast?.copy(state = "Preparando la conversión…"),
                converting = "Convirtiendo en el celular: ${plan.statusLabel()}",
            )
        }
        val t = Transcoder(app, plan)
        transcoder = t
        withContext(Dispatchers.IO) { t.start(input, startAtMs) }

        // El aparato no espera mucho por los primeros datos: se le avisa cuando ya hay algo.
        var waited = 0L
        while (t.outputBytes() < FIRST_BYTES && !t.progress.value.finished && waited < 120_000) {
            delay(300)
            waited += 300
        }
        val state = t.progress.value
        if ((t.outputBytes() < MIN_BYTES || state.error != null) && plan.video == VideoTarget.H264 && !plan.softwareVideo) {
            // El chip del celular no pudo con este vídeo: se intenta con el procesador (más lento).
            val software = plan.copy(softwareVideo = true)
            convertPlan = software
            return startConversion(software, input, startAtMs)
        }
        if (t.outputBytes() < MIN_BYTES || state.error != null) {
            val why = state.error ?: if (waited >= 120_000) "tardó demasiado en empezar (pocas fuentes en el torrent)" else "no generó vídeo"
            stopTranscoder()
            convertPlan = null
            _ui.update {
                it.copy(
                    converting = null,
                    cast = it.cast?.copy(state = "No se pudo convertir"),
                    castBlocked = "No se pudo convertir en el celular: $why.",
                    convertOffer = null,
                )
            }
            return null
        }

        convertWatch = scope.launch {
            t.progress.collect { p ->
                if (p.error != null) {
                    _ui.update { it.copy(converting = null, castMessage = "La conversión se detuvo: ${p.error}. Prueba otro stream.") }
                } else {
                    val speed = if (p.speed > 0 && !p.finished) " · %.1fx".format(p.speed) else ""
                    val label = if (p.finished) "Conversión terminada" else "Convirtiendo en el celular"
                    _ui.update { it.copy(converting = "$label: ${plan.statusLabel()}$speed") }
                }
            }
        }
        val path = app.streamServer.register(t.source, "convertido.${plan.container.extension}")
        convertPath = path
        return path
    }

    private fun castProfile() = app.compat.castProfile(app.selectedDevice.value as? TargetDevice.Chromecast)

    private fun tvProfile() = app.compat.tvProfile(app.selectedDevice.value as? TargetDevice.Tv)

    /**
     * La pista de audio que va a sonar en el televisor: la marcada "por defecto" en el archivo, o
     * la primera. El TV no deja elegir otra por DLNA ni el receptor de Google por esta vía.
     */
    private fun remoteAudioFormat(tracks: Tracks?): Format? {
        val formats = tracks?.groups.orEmpty()
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it) } }
        return formats.firstOrNull { it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 } ?: formats.firstOrNull()
    }

    /** La pista de audio número [index] (entre las de audio) del archivo. */
    private fun audioFormatAt(index: Int): Format? = lastTracks?.groups.orEmpty()
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it) } }
        .getOrNull(index)

    /** Lee el archivo real (vídeo y audio) y lo compara con el aparato al que se va a mandar. */
    private suspend fun remoteCompatReport(toTv: Boolean): CompatReport? {
        if (!tracksKnown.isCompleted) withTimeoutOrNull(20_000) { tracksKnown.await() }
        val tracks = lastTracks ?: return null
        val facts = videoFacts(tracks)
        val audio = remoteAudioFormat(tracks)?.sampleMimeType
        return if (toTv) checkTvCompat(facts, audio, tvProfile()) else checkCastCompat(facts, audio, castProfile())
    }

    /**
     * Botón "No tiene sonido": la app recuerda que en este aparato ese audio suena mudo. Desde ya,
     * los streams con ese audio salen en rojo para este aparato.
     */
    fun reportNoSound() {
        val device = app.selectedDevice.value
        val current = convertPlan
        val codec = when (current?.audio) {
            null -> audioCodecOfMime(remoteAudioFormat(lastTracks)?.sampleMimeType)
            com.sebasgl1.streamiocast.transcode.AudioTarget.AC3 -> AudioCodec.AC3
            com.sebasgl1.streamiocast.transcode.AudioTarget.AAC -> AudioCodec.AAC
            com.sebasgl1.streamiocast.transcode.AudioTarget.COPY -> audioCodecOfMime(audioFormatAt(current.audioIndex)?.sampleMimeType)
        }
        val message = when {
            device == null -> "Primero elige el aparato al que transmites."
            codec == null -> "No pude saber qué audio trae este archivo. Prueba otro stream."
            codec == AudioCodec.AAC || codec == AudioCodec.MP3 -> "El audio es ${codec.label}, que " +
                "${device.name} debería reproducir. Revisa el volumen del TV o prueba otro stream."
            else -> {
                app.compat.markMuted(device.key, codec)
                // Ya anotado: se ofrece convertir este mismo vídeo para no tener que cambiar de stream.
                // (Si ya se convertía, el plan nuevo evita también el audio que acabas de marcar.)
                val plan = buildConvertPlan(toTv = remote === dlna)?.takeIf { it != current }
                if (plan != null) {
                    offeredPlan = plan
                    _ui.update {
                        it.copy(
                            castMessage = null,
                            castBlocked = "Anotado: en ${device.name} el audio ${codec.label} suena mudo. " +
                                "Desde ahora esos streams salen en rojo para este aparato.",
                            convertOffer = offerFor(plan, remotePos()),
                            noSoundReported = true,
                        )
                    }
                    return
                }
                "Anotado: en ${device.name} el audio ${codec.label} suena mudo. Desde ahora esos streams " +
                    "salen en rojo para este aparato. Elige otro stream con otro audio."
            }
        }
        _ui.update { it.copy(castMessage = message, noSoundReported = true) }
    }

    private fun sendToCast(startAtMs: Long, force: Boolean = false) {
        castJob?.cancel()
        convertSeekJob?.cancel()
        // El celular deja de sonar ya: el vídeo va al aparato (sigue descargando por delante).
        if (remote != null) player.pause()
        castJob = scope.launch {
            val target = remote ?: return@launch
            val toTv = target === dlna
            val server = app.streamServer
            val host = withContext(Dispatchers.IO) {
                server.start()
                lanAddress()
            }
            val videoPath = path
            val directUrl = if (videoPath != null) host?.let { server.lanUrl(videoPath, it) } else request.url
            if (directUrl == null || (convertPlan != null && host == null)) {
                _ui.update {
                    it.copy(castMessage = "Para transmitir, el celular y el televisor deben estar en la misma red Wi-Fi.")
                }
                return@launch
            }

            // Se revisa el archivo real (vídeo y audio) contra el aparato antes de mandarlo.
            var warning: String? = null
            if (convertPlan == null && !force) {
                _ui.update { it.copy(cast = it.cast?.copy(state = "Revisando el formato…")) }
                val report = remoteCompatReport(toTv)
                // Se recuerda el resultado: la lista de streams lo mostrará en vez de adivinar por el nombre.
                val deviceKey = app.selectedDevice.value?.key
                val streamKey = streamKeyOf(request.infoHash, request.fileIdx, request.url)
                if (report != null && deviceKey != null && streamKey != null) {
                    when {
                        report.blocking.isNotEmpty() -> app.compat.markVerified(deviceKey, streamKey, report.blocking.first())
                        report.warnings.isEmpty() -> app.compat.markVerified(deviceKey, streamKey, null)
                    }
                }
                if (report != null && report.blocking.isNotEmpty()) {
                    val plan = buildConvertPlan(toTv)
                    if (plan != null && request.autoConvert) {
                        // Venías convirtiendo el episodio anterior: este sigue igual, sin preguntar.
                        convertPlan = plan
                    } else {
                        offeredPlan = plan
                        _ui.update {
                            it.copy(
                                castBlocked = report.blocking.joinToString("\n"),
                                convertOffer = plan?.let { p -> offerFor(p, startAtMs) },
                                cast = it.cast?.copy(state = "No compatible"),
                            )
                        }
                        return@launch
                    }
                } else {
                    warning = report?.warnings?.joinToString("\n")?.takeIf { it.isNotEmpty() }
                    val ok = report != null && warning == null
                    val name = app.selectedDevice.value?.name ?: "el televisor"
                    _ui.update { it.copy(compatOk = if (ok) "Formato revisado: vídeo y audio compatibles con $name" else null) }
                }
            } else {
                _ui.update { it.copy(compatOk = null) }
            }
            _ui.update { it.copy(castBlocked = null, convertOffer = null, noSoundReported = false) }

            // El celular deja de leer el torrent para no competir con el televisor (o con FFmpeg).
            knownDurationMs()
            player.pause()
            player.stop()

            // Con conversión, al aparato le llega la versión convertida, que empieza en startAtMs.
            val plan = convertPlan
            var url = directUrl
            var sourcePath = videoPath
            if (plan != null) {
                val input = videoPath?.let { server.localUrl(it) } ?: directUrl
                val livePath = startConversion(plan, input, startAtMs) ?: return@launch
                url = server.lanUrl(livePath, host!!)
                sourcePath = livePath
            } else {
                stopTranscoder()
                convertBaseMs = 0
                _ui.update { it.copy(converting = null) }
            }
            pendingSeekMs = null
            val live = plan != null
            val duration = knownDurationMs().let { if (live) (it - startAtMs).coerceAtLeast(0) else it }

            val media = if (toTv) {
                val srt = if (host != null) dlnaSubtitleUrl(host) else null
                // Los Samsung también leen el subtítulo de esta cabecera de la respuesta del vídeo.
                sourcePath?.let { server.setHeaders(it, if (srt != null) mapOf("CaptionInfo.sec" to srt) else emptyMap()) }
                val name = stream?.fileName ?: request.filename ?: directUrl.substringBefore('?')
                CastMedia(
                    url = url,
                    contentType = plan?.container?.castMime ?: castContentType(),
                    title = request.title,
                    startAtMs = if (live) 0 else startAtMs,
                    subtitleTracks = emptyList(),
                    activeSubtitleId = null,
                    subtitleLang = currentExternalLang(),
                    subtitleScale = 1f,
                    videoMime = plan?.container?.dlnaMime ?: dlnaMime(name, dlna.device?.isSamsung == true),
                    srtUrl = srt,
                    durationMs = duration,
                    live = live,
                )
            } else {
                val (tracks, activeTrack) = if (host != null) castSubtitleTracks(host) else noTracks()
                CastMedia(
                    url = url,
                    contentType = plan?.container?.castMime ?: castContentType(),
                    title = request.title,
                    startAtMs = if (live) 0 else startAtMs,
                    subtitleTracks = tracks,
                    activeSubtitleId = activeTrack,
                    subtitleLang = currentExternalLang(),
                    subtitleScale = CAST_SCALE[_ui.value.subtitleSize.coerceIn(0, 2)],
                    durationMs = duration,
                    live = live,
                )
            }

            target.load(media)
            remoteLoaded = true
            val embeddedNote = if (target === cast && media.subtitleTracks.isEmpty() && _ui.value.textTracks.any { it.selected }) {
                "Los subtítulos internos del archivo no se pueden transmitir. Elige uno externo en \"Audio y subtítulos\"."
            } else {
                null
            }
            _ui.update {
                it.copy(cast = it.cast?.copy(state = "Cargando en el televisor…"), castMessage = embeddedNote ?: warning)
            }
        }
    }

    /** La duración que leyó el reproductor del celular (0 si aún no la sabe). */
    private fun knownDurationMs(): Long {
        // Se recuerda: al detener el reproductor del celular la duración deja de estar disponible.
        player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { lastKnownDuration = it }
        if (lastKnownDuration <= 0) lastKnownDuration = savedEntry?.durationMs ?: 0L
        return lastKnownDuration
    }

    /** El subtítulo elegido en SRT, para televisores DLNA (Samsung). */
    private fun dlnaSubtitleUrl(host: String): String? {
        clearCastSubtitles()
        val list = cues
        if (_ui.value.externalSelected == null || list.isEmpty()) return null
        val offset = _ui.value.subtitleOffsetMs
        val shift = convertShift()
        val p = app.streamServer.register(
            // Convertido: el TV cuenta desde donde empezó la conversión, así que el subtítulo se corre.
            LazySource("text/srt; charset=utf-8") { toSrt(list, offset - shift).toByteArray(Charsets.UTF_8) },
            "subtitulos.srt",
        )
        castSubtitlePaths = listOf(p)
        return app.streamServer.lanUrl(p, host)
    }

    private fun noTracks(): Pair<List<CastSubtitleTrack>, Long?> = emptyList<CastSubtitleTrack>() to null

    /**
     * El subtítulo elegido como WebVTT (lo único que acepta el Chromecast), en 41 versiones con
     * retraso de −10 s a +10 s cada medio segundo: cambiar el retraso es solo cambiar de pista.
     * Cada versión se genera recién cuando el TV la pide.
     */
    private fun castSubtitleTracks(host: String): Pair<List<CastSubtitleTrack>, Long?> {
        clearCastSubtitles()
        val list = cues
        if (_ui.value.externalSelected == null || list.isEmpty()) return noTracks()
        val base = _ui.value.subtitleOffsetMs
        val shift = convertShift()
        val tracks = mutableListOf<CastSubtitleTrack>()
        val paths = mutableListOf<String>()
        val offsets = HashMap<Long, Long>()
        for (step in -OFFSET_STEPS..OFFSET_STEPS) {
            val offset = base + step * OFFSET_STEP_MS
            val id = FIRST_TRACK_ID + step + OFFSET_STEPS
            val p = app.streamServer.register(
                LazySource("text/vtt; charset=utf-8") { toWebVtt(list, offset - shift).toByteArray(Charsets.UTF_8) },
                "subtitulos.vtt",
            )
            paths += p
            tracks += CastSubtitleTrack(id, app.streamServer.lanUrl(p, host))
            offsets[offset] = id
        }
        castSubtitlePaths = paths
        castOffsetTracks = offsets
        return tracks to offsets[base]
    }

    private fun clearCastSubtitles() {
        castSubtitlePaths.forEach { app.streamServer.unregister(it) }
        castSubtitlePaths = emptyList()
        castOffsetTracks = emptyMap()
    }

    private fun currentExternalLang(): String? {
        val id = _ui.value.externalSelected ?: return null
        return _ui.value.externalSubs.firstOrNull { it.id == id }?.lang?.let { normalizeLang(it) }
    }

    /** El receptor de Google reproduce MKV con H.264 si se le anuncia como vídeo progresivo. */
    private fun castContentType(): String {
        val name = (stream?.fileName ?: request.filename ?: request.url?.substringBefore('?') ?: "").lowercase()
        return when {
            name.endsWith(".webm") -> "video/webm"
            name.endsWith(".m3u8") -> "application/x-mpegurl"
            else -> "video/mp4"
        }
    }

    // --- Utilidades -------------------------------------------------------------------

    /** Cierra la sesión fuera del callback actual (desde el Cast SDK no conviene hacerlo en caliente). */
    private fun closeLater() {
        Handler(Looper.getMainLooper()).post {
            if (isOpen && !attached && app.session === this) app.closeSession()
        }
    }

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

    /**
     * Guarda dónde ibas y libera todo: torrent, servidor, reproductor y vídeo en el televisor.
     * [keepService]: se pasa al siguiente episodio; el servicio sigue para que Android no corte
     * la descarga (con la app en segundo plano no se podría volver a iniciar).
     */
    fun close(keepService: Boolean = false) {
        if (!isOpen) return
        saveProgress()
        isOpen = false
        countdownJob?.cancel()
        waitJob?.cancel()
        convertSeekJob?.cancel()
        pushSync(force = true)
        remote?.stopMedia()
        cast.release()
        dlna.release()
        player.release()
        stopTranscoder()
        path?.let { app.streamServer.unregister(it) }
        clearCastSubtitles()
        stream?.let { app.torrents.close(it) }
        if (!keepService) StreamingService.stop(app)
        scope.cancel()
    }

    companion object {
        private const val HASH_TIMEOUT_MS = 60_000L

        /** Lo convertido que se espera antes de avisarle al aparato (y el mínimo para no rendirse). */
        private const val FIRST_BYTES = 1_500_000L
        private const val MIN_BYTES = 64_000L
        private val CAST_SCALE = floatArrayOf(0.8f, 1f, 1.3f)
        private const val OFFSET_STEPS = 20
        private const val OFFSET_STEP_MS = 500L
        private const val FIRST_TRACK_ID = 100L
        private const val DLNA_FAILED =
            "El televisor no pudo reproducir este archivo. Prueba con otro stream."
        private const val CAST_FAILED =
            "El Chromecast no pudo reproducir este archivo. Suele ser por el formato (HEVC, 10 bits, " +
                "audio DTS…). Prueba un stream marcado como Compatible."
    }
}
