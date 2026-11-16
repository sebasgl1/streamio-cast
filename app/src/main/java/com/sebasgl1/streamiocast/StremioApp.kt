package com.sebasgl1.streamiocast

import android.app.Application
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.sebasgl1.streamiocast.cast.CompatMemory
import com.sebasgl1.streamiocast.cast.TargetDevice
import com.sebasgl1.streamiocast.data.AddonRepository
import com.sebasgl1.streamiocast.data.PlaybackPrefs
import com.sebasgl1.streamiocast.data.StremioSync
import com.sebasgl1.streamiocast.dlna.DlnaDevice
import com.sebasgl1.streamiocast.history.WatchHistory
import com.sebasgl1.streamiocast.player.PlaybackRequest
import com.sebasgl1.streamiocast.player.PlaybackSession
import com.sebasgl1.streamiocast.stream.LocalStreamServer
import com.sebasgl1.streamiocast.subtitles.SubtitleRepository
import com.sebasgl1.streamiocast.torrent.TorrentEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StremioApp : Application() {
    lateinit var repo: AddonRepository
        private set

    /** Se crean al primer uso: la sesión de torrents no arranca hasta que reproduces algo. */
    val torrents: TorrentEngine by lazy { TorrentEngine(this) }
    val streamServer: LocalStreamServer by lazy { LocalStreamServer() }
    val subtitles: SubtitleRepository by lazy { SubtitleRepository(repo) }
    val playbackPrefs: PlaybackPrefs by lazy { PlaybackPrefs(this) }
    val history: WatchHistory by lazy { WatchHistory(this) }
    val sync: StremioSync by lazy { StremioSync(repo, history) }
    val compat: CompatMemory by lazy { CompatMemory(this) }

    // --- Aparato elegido (Chromecast o TV), desde cualquier pantalla ------------------------

    /** El televisor DLNA elegido (sigue elegido para el siguiente episodio hasta que lo sueltes). */
    @Volatile
    var dlnaDevice: DlnaDevice? = null
        set(value) {
            field = value
            publishDevice()
        }

    @Volatile
    private var castDevice: TargetDevice.Chromecast? = null

    private val _device = MutableStateFlow<TargetDevice?>(null)

    /** A qué se va a transmitir: el TV DLNA si hay uno elegido, si no el Chromecast conectado. */
    val selectedDevice: StateFlow<TargetDevice?> = _device

    private fun publishDevice() {
        _device.value = dlnaDevice?.let { TargetDevice.Tv(it) } ?: castDevice
    }

    private val castContext: CastContext? by lazy { runCatching { CastContext.getSharedInstance(this) }.getOrNull() }

    private val castWatcher = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = castConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = castConnected(session)
        override fun onSessionEnded(session: CastSession, error: Int) = castGone()
        override fun onSessionStartFailed(session: CastSession, error: Int) = castGone()
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = castGone()
    }

    private fun castConnected(session: CastSession) {
        val device = session.castDevice
        castDevice = TargetDevice.Chromecast(device?.friendlyName ?: "Chromecast", device?.modelName)
        publishDevice()
    }

    private fun castGone() {
        castDevice = null
        publishDevice()
    }

    /** Se eligió un Chromecast en la lista (la conexión la hace el propio Cast SDK). */
    fun selectChromecast() {
        val s = session?.takeIf { it.isOpen }
        if (s != null) s.prepareForChromecast() else dlnaDevice = null
    }

    /** Se eligió un TV DLNA: si hay un vídeo sonando pasa al TV; si no, queda listo para el próximo. */
    fun selectDlna(device: DlnaDevice) {
        val s = session?.takeIf { it.isOpen }
        if (s != null) {
            s.connectDlna(device)
        } else {
            dlnaDevice = device
            castContext?.sessionManager?.endCurrentSession(true)
        }
    }

    /** Dejar de transmitir (o soltar el aparato elegido antes de reproducir). */
    fun stopDevice() {
        val s = session?.takeIf { it.isOpen }
        if (s != null && s.isCasting) {
            s.stopCasting()
        } else {
            dlnaDevice = null
            castContext?.sessionManager?.endCurrentSession(true)
        }
    }

    // --- Reproducción ------------------------------------------------------------------

    /** Stream elegido en la lista, a la espera de que lo recoja la pantalla de reproducción. */
    @Volatile
    var pendingPlayback: PlaybackRequest? = null

    /** La reproducción en curso. Vive aquí (no en la pantalla) para sobrevivir a que se recree. */
    private val _session = MutableStateFlow<PlaybackSession?>(null)
    val sessionFlow: StateFlow<PlaybackSession?> = _session

    val session: PlaybackSession?
        get() = _session.value

    /** Cada vez que la notificación pide abrir el reproductor, sube este contador. */
    private val _openPlayer = MutableStateFlow(0)
    val openPlayerRequests: StateFlow<Int> = _openPlayer

    fun requestOpenPlayer() {
        _openPlayer.value += 1
    }

    /** La sesión abierta, o una nueva con el stream pendiente. Solo desde el hilo principal. */
    fun obtainSession(keepService: Boolean = false): PlaybackSession? {
        val pending = pendingPlayback
        val current = session?.takeIf { it.isOpen }
        if (current != null && pending == null) return current
        if (pending == null) return null
        // Se eligió otro stream: el anterior se cierra.
        current?.close(keepService)
        pendingPlayback = null
        return PlaybackSession(this, pending).also { _session.value = it }
    }

    /** Siguiente episodio: reemplaza la sesión actual (la pantalla se engancha sola a la nueva). */
    fun startNext(request: PlaybackRequest) {
        val wasAttached = session?.attached == true
        pendingPlayback = request
        obtainSession(keepService = true)?.attached = wasAttached
    }

    fun closeSession() {
        session?.close()
        _session.value = null
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        repo = AddonRepository(this)
        // Restos de una conversión que quedó a medias (la app se cerró): se borran.
        Thread { com.sebasgl1.streamiocast.transcode.cleanUpConversions(this) }.start()
        // Si el Chromecast ya estaba conectado (desde otra app o una sesión anterior), se nota de una.
        castContext?.sessionManager?.let { manager ->
            manager.addSessionManagerListener(castWatcher, CastSession::class.java)
            manager.currentCastSession?.takeIf { it.isConnected }?.let { castConnected(it) }
        }
    }
}
