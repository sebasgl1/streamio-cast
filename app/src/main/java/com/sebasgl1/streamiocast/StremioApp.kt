package com.sebasgl1.streamiocast

import android.app.Application
import com.sebasgl1.streamiocast.data.AddonRepository
import com.sebasgl1.streamiocast.data.PlaybackPrefs
import com.sebasgl1.streamiocast.player.PlaybackRequest
import com.sebasgl1.streamiocast.stream.LocalStreamServer
import com.sebasgl1.streamiocast.subtitles.SubtitleRepository
import com.sebasgl1.streamiocast.torrent.TorrentEngine

class StremioApp : Application() {
    lateinit var repo: AddonRepository
        private set

    /** Se crean al primer uso: la sesión de torrents no arranca hasta que reproduces algo. */
    val torrents: TorrentEngine by lazy { TorrentEngine(this) }
    val streamServer: LocalStreamServer by lazy { LocalStreamServer() }
    val subtitles: SubtitleRepository by lazy { SubtitleRepository(repo) }
    val playbackPrefs: PlaybackPrefs by lazy { PlaybackPrefs(this) }

    /** Stream elegido en la lista, a la espera de que lo recoja la pantalla de reproducción. */
    @Volatile
    var pendingPlayback: PlaybackRequest? = null

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        repo = AddonRepository(this)
    }
}
