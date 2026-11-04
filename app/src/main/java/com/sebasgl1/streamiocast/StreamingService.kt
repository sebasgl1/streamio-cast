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

    /** Lo que muestra la notificación. */
    private data class NotifState(val title: String, val playing: Boolean, val where: String)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as StremioApp
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
                    .collect { state -> if (started) notify(build(state)) }
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
                (application as StremioApp).closeSession()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
                ensureChannel()
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

    private fun build(state: NotifState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(state.title)
            .setContentText(if (state.playing) state.where else "${state.where} · En pausa")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
                androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2),
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
