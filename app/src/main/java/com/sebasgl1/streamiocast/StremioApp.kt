package com.sebasgl1.streamiocast

import android.app.Application
import com.sebasgl1.streamiocast.data.AddonRepository
import com.sebasgl1.streamiocast.data.PlaybackPrefs
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
    fun obtainSession(): PlaybackSession? {
        val pending = pendingPlayback
        val current = session?.takeIf { it.isOpen }
        if (current != null && pending == null) return current
        if (pending == null) return null
        // Se eligió otro stream: el anterior se cierra.
        current?.close()
        pendingPlayback = null
        return PlaybackSession(this, pending).also { _session.value = it }
    }

    /** Siguiente episodio: reemplaza la sesión actual (la pantalla se engancha sola a la nueva). */
    fun startNext(request: PlaybackRequest) {
        val wasAttached = session?.attached == true
        pendingPlayback = request
        obtainSession()?.attached = wasAttached
    }

    fun closeSession() {
        session?.close()
        _session.value = null
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        repo = AddonRepository(this)
    }
}
