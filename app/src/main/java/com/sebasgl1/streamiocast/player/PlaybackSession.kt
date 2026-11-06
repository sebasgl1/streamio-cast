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
            _ui.update { it.copy(cast = CastUi(device), castMessage = null) }
            // Si aún se está llenando el buffer, se manda al TV cuando termine (o le des reproducir).
            if (_ui.value.phase is PlayerPhase.Playing && gateOpen) sendToCast(player.currentPosition)
        }

        override fun onDisconnected(lastPositionMs: Long) {
            // Se pasó de un aparato a otro (por ejemplo, del Chromecast al TV): nada que hacer.
            if (remote != null) return
            app.dlnaDevice = null
            saveProgress()
            castJob?.cancel()
            clearCastSubtitles()
            _ui.update { it.copy(cast = null, castMessage = null) }
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
            _ui.update { s -> s.copy(cast = s.cast?.copy(positionMs = positionMs, durationMs = durationMs)) }
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
        val r = remote
        if (r != null) r.seek(0) else player.seekTo(0)
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
            val pick = outcome?.let { pickNextStream(it.results, request.bingeGroup, device) }
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
            app.startNext(pick.toPlayback(next).copy(autoNext = true))
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
            remote?.let { sendToCast(it.positionMs) }
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
                    sendToCast(remote?.positionMs ?: 0)
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
        }
    }

    fun selectEmbeddedText(id: String) = selectEmbeddedText(id, record = true)

    private fun selectEmbeddedText(id: String, record: Boolean) {
        val (group, index) = trackRefs[id] ?: return
        clearExternal()
        remote?.let { r ->
            r.hideSubtitles()
            if (r === dlna) sendToCast(r.positionMs)
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
            if (r === dlna) sendToCast(r.positionMs) // el TV DLNA solo los quita recargando
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
        remote?.seek(positionMs)
    }

    fun castSkip(deltaMs: Long) {
        val r = remote ?: return
        val c = _ui.value.cast ?: return
        val max = if (c.durationMs > 0) c.durationMs else Long.MAX_VALUE
        r.seek((r.positionMs + deltaMs).coerceIn(0, max))
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
        _ui.update { it.copy(castBlocked = null) }
        sendToCast(player.currentPosition, force = true)
    }

    private suspend fun castCompatReport(): CompatReport? {
        if (!tracksKnown.isCompleted) withTimeoutOrNull(20_000) { tracksKnown.await() }
        val tracks = lastTracks ?: return null
        val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
        val video = videoGroup?.let { g ->
            g.getTrackFormat((0 until g.length).firstOrNull { g.isTrackSelected(it) } ?: 0)
        }
        // En el Chromecast suena la primera pista de audio del archivo.
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.getTrackFormat(0)
        return checkCastCompat(
            video?.let { VideoFacts(it.sampleMimeType, it.width, it.height, it.codecs, it.frameRate) },
            audio?.sampleMimeType,
        )
    }

    private fun sendToCast(startAtMs: Long, force: Boolean = false) {
        castJob?.cancel()
        castJob = scope.launch {
            val target = remote ?: return@launch
            val server = app.streamServer
            val host = withContext(Dispatchers.IO) {
                server.start()
                lanAddress()
            }
            val videoPath = path
            val url = if (videoPath != null) host?.let { server.lanUrl(videoPath, it) } else request.url
            if (url == null) {
                _ui.update {
                    it.copy(castMessage = "Para transmitir, el celular y el televisor deben estar en la misma red Wi-Fi.")
                }
                return@launch
            }

            // Chromecast: se revisa el vídeo real (códec, resolución, 10 bits) antes de mandarlo.
            var warning: String? = null
            if (target === cast && !force) {
                _ui.update { it.copy(cast = it.cast?.copy(state = "Revisando el formato…")) }
                val report = castCompatReport()
                if (report != null && report.blocking.isNotEmpty()) {
                    _ui.update {
                        it.copy(castBlocked = report.blocking.joinToString("\n"), cast = it.cast?.copy(state = "No compatible"))
                    }
                    return@launch
                }
                warning = report?.warnings?.joinToString("\n")?.takeIf { it.isNotEmpty() }
            }
            _ui.update { it.copy(castBlocked = null) }

            val media = if (target === dlna) {
                val srt = if (host != null) dlnaSubtitleUrl(host) else null
                // Los Samsung también leen el subtítulo de esta cabecera de la respuesta del vídeo.
                videoPath?.let { server.setHeaders(it, if (srt != null) mapOf("CaptionInfo.sec" to srt) else emptyMap()) }
                val name = stream?.fileName ?: request.filename ?: url.substringBefore('?')
                CastMedia(
                    url = url,
                    contentType = castContentType(),
                    title = request.title,
                    startAtMs = startAtMs,
                    subtitleTracks = emptyList(),
                    activeSubtitleId = null,
                    subtitleLang = currentExternalLang(),
                    subtitleScale = 1f,
                    videoMime = dlnaMime(name, dlna.device?.isSamsung == true),
                    srtUrl = srt,
                )
            } else {
                val (tracks, activeTrack) = if (host != null) castSubtitleTracks(host) else noTracks()
                CastMedia(
                    url = url,
                    contentType = castContentType(),
                    title = request.title,
                    startAtMs = startAtMs,
                    subtitleTracks = tracks,
                    activeSubtitleId = activeTrack,
                    subtitleLang = currentExternalLang(),
                    subtitleScale = CAST_SCALE[_ui.value.subtitleSize.coerceIn(0, 2)],
                )
            }

            // El celular deja de leer el torrent para no competir con el televisor.
            player.pause()
            player.stop()

            target.load(media)
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

    /** El subtítulo elegido en SRT, para televisores DLNA (Samsung). */
    private fun dlnaSubtitleUrl(host: String): String? {
        clearCastSubtitles()
        val list = cues
        if (_ui.value.externalSelected == null || list.isEmpty()) return null
        val offset = _ui.value.subtitleOffsetMs
        val p = app.streamServer.register(
            LazySource("text/srt; charset=utf-8") { toSrt(list, offset).toByteArray(Charsets.UTF_8) },
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
        val tracks = mutableListOf<CastSubtitleTrack>()
        val paths = mutableListOf<String>()
        val offsets = HashMap<Long, Long>()
        for (step in -OFFSET_STEPS..OFFSET_STEPS) {
            val offset = base + step * OFFSET_STEP_MS
            val id = FIRST_TRACK_ID + step + OFFSET_STEPS
            val p = app.streamServer.register(
                LazySource("text/vtt; charset=utf-8") { toWebVtt(list, offset).toByteArray(Charsets.UTF_8) },
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

    /** Guarda dónde ibas y libera todo: torrent, servidor, reproductor y vídeo en el televisor. */
    fun close() {
        if (!isOpen) return
        saveProgress()
        isOpen = false
        countdownJob?.cancel()
        waitJob?.cancel()
        pushSync(force = true)
        remote?.stopMedia()
        cast.release()
        dlna.release()
        player.release()
        path?.let { app.streamServer.unregister(it) }
        clearCastSubtitles()
        stream?.let { app.torrents.close(it) }
        StreamingService.stop(app)
        scope.cancel()
    }

    companion object {
        private const val HASH_TIMEOUT_MS = 60_000L
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
