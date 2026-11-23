package com.sebasgl1.streamiocast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sebasgl1.streamiocast.player.PlayerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mantiene viva la reproducción: la descarga del torrent sigue y el Chromecast sigue recibiendo el
 * vídeo aunque apagues la pantalla o uses otras apps. Muestra una notificación con controles.
 */
class StreamingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    /**
     * Sesión multimedia: con ella Android pone la notificación en el reproductor fijo de arriba
     * (como Spotify o YouTube) y en la pantalla de bloqueo, en vez de en las "silenciosas" del fondo.
     */
    private lateinit var mediaSession: MediaSessionCompat
    private var lastState: NotifState? = null

    /** Lo que muestra la notificación. */
    private data class NotifState(val title: String, val playing: Boolean, val where: String)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as StremioApp
        mediaSession = MediaSessionCompat(this, "StreamioCast").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = toggleIf(playing = false)
                override fun onPause() = toggleIf(playing = true)
                override fun onSeekTo(pos: Long) {
                    app.session?.seekTo(pos)
                    refreshPlayback()
                }
                override fun onRewind() { app.session?.seekBy(-10_000) }
                override fun onFastForward() { app.session?.seekBy(30_000) }
                override fun onStop() = stopAll()
                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        ACTION_BACK -> app.session?.seekBy(-10_000)
                        ACTION_FORWARD -> app.session?.seekBy(30_000)
                        ACTION_STOP -> stopAll()
                    }
                    refreshPlayback()
                }
            })
            setSessionActivity(openPlayerIntent())
            isActive = true
        }
        // La barra de avance se corrige de vez en cuando (el sistema la extrapola entre medio).
        scope.launch {
            while (true) {
                delay(5_000)
                if (started) refreshPlayback()
            }
        }
        scope.launch {
            app.sessionFlow.collectLatest { session ->
                if (session == null) return@collectLatest
                session.ui
                    .map { ui ->
                        val casting = ui.cast
                        NotifState(
                            title = ui.title,
                            playing = casting?.playing ?: ui.localPlaying,
                            where = when {
                                casting != null -> "En ${casting.device}"
                                ui.phase is PlayerPhase.Preparing -> "Preparando…"
                                else -> "En el celular"
                            },
                        )
                    }
                    .distinctUntilChanged()
                    .collect { state ->
                        lastState = state
                        updateSession(state)
                        if (started) notify(build(state))
                    }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = (application as StremioApp).session
        when (intent?.action) {
            ACTION_TOGGLE -> session?.togglePlay()
            ACTION_BACK -> session?.seekBy(-10_000)
            ACTION_FORWARD -> session?.seekBy(30_000)
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
                ensureChannel()
                updateSession(NotifState(title, false, "Preparando…"))
                val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
                ServiceCompat.startForeground(this, NOTIFICATION_ID, build(NotifState(title, false, "Preparando…")), type)
                started = true
                acquireLocks()
            }
        }
        // Sin sesión no hay nada que mantener vivo.
        if (session == null && intent?.action != null) stopSelf()
        return START_NOT_STICKY
    }

    private fun openPlayerIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun toggleIf(playing: Boolean) {
        if (lastState?.playing == playing) (application as StremioApp).session?.togglePlay()
    }

    private fun stopAll() {
        (application as StremioApp).closeSession()
        stopSelf()
    }

    /** Título, duración y estado para el reproductor de arriba y la pantalla de bloqueo. */
    private fun updateSession(state: NotifState) {
        val session = (application as StremioApp).session
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.where)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, session?.controlsDurationMs?.takeIf { it > 0 } ?: -1L)
                .build(),
        )
        refreshPlayback()
    }

    private fun refreshPlayback() {
        val state = lastState ?: return
        val session = (application as StremioApp).session
        val position = session?.controlsPositionMs ?: 0L
        // Android 13+ arma los botones del reproductor de arriba con esto (no con los de la notificación).
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (state.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    position,
                    if (state.playing) 1f else 0f,
                )
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .addCustomAction(ACTION_BACK, "−10 s", android.R.drawable.ic_media_rew)
                .addCustomAction(ACTION_FORWARD, "+30 s", android.R.drawable.ic_media_ff)
                .addCustomAction(ACTION_STOP, "Detener", android.R.drawable.ic_menu_close_clear_cancel)
                .build(),
        )
        // La duración puede conocerse después de empezar (al cargar el vídeo).
        val duration = session?.controlsDurationMs ?: 0L
        val known = mediaSession.controller.metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: -1L
        if (duration > 0 && known != duration) {
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.where)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                    .build(),
            )
        }
    }

    private fun build(state: NotifState): Notification {
        val open = openPlayerIntent()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(state.title)
            .setContentText(if (state.playing) state.where else "${state.where} · En pausa")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_rew, "−10 s", action(ACTION_BACK))
            .addAction(
                if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.playing) "Pausar" else "Reproducir",
                action(ACTION_TOGGLE),
            )
            .addAction(android.R.drawable.ic_media_ff, "+30 s", action(ACTION_FORWARD))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", action(ACTION_STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, StreamingService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streamiocast:streaming").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "streamiocast:streaming").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /** Quitaste la app de recientes: se cierra la reproducción y se deja de descargar. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as? StremioApp)?.closeSession()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching {
            mediaSession.isActive = false
            mediaSession.release()
        }
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "streaming"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_TITLE = "title"
        private const val ACTION_TOGGLE = "com.sebasgl1.streamiocast.TOGGLE"
        private const val ACTION_BACK = "com.sebasgl1.streamiocast.BACK"
        private const val ACTION_FORWARD = "com.sebasgl1.streamiocast.FORWARD"
        private const val ACTION_STOP = "com.sebasgl1.streamiocast.STOP"

        fun start(context: Context, title: String) {
            val intent = Intent(context, StreamingService::class.java).putExtra(EXTRA_TITLE, title)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamingService::class.java))
        }
    }
}
