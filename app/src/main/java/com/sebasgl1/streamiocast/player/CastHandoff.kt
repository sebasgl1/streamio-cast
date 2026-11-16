package com.sebasgl1.streamiocast.player

import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/** Estado del Chromecast para la pantalla. */
data class CastUi(
    val device: String,
    val state: String = "Conectando…",
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** Una pista de subtítulos para el televisor (el mismo subtítulo con distinto retraso). */
data class CastSubtitleTrack(val id: Long, val url: String)

/** Lo que el Chromecast debe reproducir. */
data class CastMedia(
    val url: String,
    val contentType: String,
    val title: String,
    val startAtMs: Long,
    val subtitleTracks: List<CastSubtitleTrack>,
    val activeSubtitleId: Long?,
    val subtitleLang: String?,
    val subtitleScale: Float,
    /** Tipo real del archivo (para televisores DLNA). */
    val videoMime: String = "video/mp4",
    /** Subtítulo en SRT (televisores DLNA/Samsung). */
    val srtUrl: String? = null,
    /** Duración que ya conoce el celular (los TV DLNA a veces no la informan). 0 = desconocida. */
    val durationMs: Long = 0,
    /** Vídeo convertido en el celular: se va generando, sin tamaño conocido ni salto por bytes. */
    val live: Boolean = false,
)

/**
 * Envoltorio del Cast SDK: conecta/desconecta, carga el vídeo en el receptor por defecto de Google
 * y reenvía su estado. Todo se llama desde el hilo principal.
 */
class CastHandoff(context: Context, private val events: Events) : RemotePlayer {

    override val supportsTrackSwitch: Boolean = true

    interface Events {
        fun onConnected(device: String)
        fun onDisconnected(lastPositionMs: Long)
        fun onStatus(state: String, playing: Boolean, failed: Boolean, finished: Boolean)
        fun onProgress(positionMs: Long, durationMs: Long)
        fun onConnectFailed()
    }

    private val castContext: CastContext? = runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    private var session: CastSession? = null
    private var lastPositionMs = 0L

    /**
     * "Terminó" o "falló" solo cuentan para un vídeo que cargó esta sesión y que llegó a sonar.
     * Al pasar al siguiente episodio, el Chromecast aún muestra el estado del anterior ("terminó"),
     * y sin esto la sesión nueva creería que su vídeo ya acabó.
     */
    private var loadedHere = false
    private var started = false
    private var endReported = false

    override val isConnected: Boolean
        get() = session?.isConnected == true

    /** ¿Hay un Chromecast conectado aunque esta sesión aún no lo haya tomado? */
    fun hasConnectedDevice(): Boolean =
        isConnected || castContext?.sessionManager?.currentCastSession?.isConnected == true

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = publishStatus()
    }

    private val progressListener = RemoteMediaClient.ProgressListener { progress, duration ->
        lastPositionMs = progress
        events.onProgress(progress, duration)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionEnded(session: CastSession, error: Int) = detach()
        override fun onSessionStartFailed(session: CastSession, error: Int) = events.onConnectFailed()
        override fun onSessionEnding(session: CastSession) {
            session.remoteMediaClient?.approximateStreamPosition?.let { if (it > 0) lastPositionMs = it }
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    init {
        castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
    }

    /** Si ya había un Chromecast conectado antes de abrir el vídeo, lo toma. */
    fun adoptExistingSession() {
        val current = castContext?.sessionManager?.currentCastSession ?: return
        if (current.isConnected && session == null) attach(current)
    }

    private fun attach(newSession: CastSession) {
        session = newSession
        newSession.remoteMediaClient?.let { remote ->
            remote.unregisterCallback(remoteCallback)
            remote.registerCallback(remoteCallback)
            remote.removeProgressListener(progressListener)
            remote.addProgressListener(progressListener, 1000)
        }
        events.onConnected(newSession.castDevice?.friendlyName ?: "Chromecast")
    }

    private fun detach() {
        session?.remoteMediaClient?.let { remote ->
            remote.unregisterCallback(remoteCallback)
            remote.removeProgressListener(progressListener)
        }
        session = null
        events.onDisconnected(lastPositionMs)
    }

    override fun load(media: CastMedia) {
        val remote = session?.remoteMediaClient ?: return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
        }
        val builder = MediaInfo.Builder(media.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.contentType)
            .setMetadata(metadata)
            .setTextTrackStyle(textStyle(media.subtitleScale))
        if (media.live && media.durationMs > 0) builder.setStreamDuration(media.durationMs)
        if (media.subtitleTracks.isNotEmpty()) {
            builder.setMediaTracks(
                media.subtitleTracks.map { track ->
                    MediaTrack.Builder(track.id, MediaTrack.TYPE_TEXT)
                        .setName("Subtítulos")
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .setContentId(track.url)
                        .setContentType("text/vtt")
                        .setLanguage(media.subtitleLang ?: "es")
                        .build()
                },
            )
        }
        val active = media.activeSubtitleId
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(builder.build())
            .setAutoplay(true)
            .setCurrentTime(media.startAtMs)
            .apply { if (active != null) setActiveTrackIds(longArrayOf(active)) }
            .build()
        lastPositionMs = media.startAtMs
        loadedHere = true
        started = false
        endReported = false
        remote.load(request)
    }

    override val positionMs: Long
        get() = session?.remoteMediaClient?.approximateStreamPosition?.takeIf { it > 0 } ?: lastPositionMs

    override fun togglePlayback() {
        session?.remoteMediaClient?.togglePlayback()
    }

    override fun seek(positionMs: Long) {
        session?.remoteMediaClient?.seek(MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0)).build())
    }

    override fun setSubtitleScale(scale: Float) {
        session?.remoteMediaClient?.setTextTrackStyle(textStyle(scale))
    }

    /** Cambia de pista de subtítulos sin recargar el vídeo (así se ajusta el retraso). */
    override fun activateTrack(id: Long) {
        session?.remoteMediaClient?.setActiveMediaTracks(longArrayOf(id))
    }

    override fun hideSubtitles() {
        session?.remoteMediaClient?.setActiveMediaTracks(longArrayOf())
    }

    /** Detiene el vídeo en el televisor (sin desconectar el Chromecast). */
    override fun stopMedia() {
        session?.remoteMediaClient?.stop()
    }

    override fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    override fun release() {
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        session?.remoteMediaClient?.let { remote ->
            remote.unregisterCallback(remoteCallback)
            remote.removeProgressListener(progressListener)
        }
        session = null
    }

    private fun publishStatus() {
        val status = session?.remoteMediaClient?.mediaStatus ?: return
        val idle = status.playerState == MediaStatus.PLAYER_STATE_IDLE
        if (status.playerState == MediaStatus.PLAYER_STATE_PLAYING ||
            status.playerState == MediaStatus.PLAYER_STATE_PAUSED
        ) {
            started = true
        }
        val failed = loadedHere && !endReported && idle && status.idleReason == MediaStatus.IDLE_REASON_ERROR
        val finished = loadedHere && started && !endReported && idle &&
            status.idleReason == MediaStatus.IDLE_REASON_FINISHED
        if (failed || finished) endReported = true
        val state = when (status.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> "Reproduciendo"
            MediaStatus.PLAYER_STATE_PAUSED -> "En pausa"
            MediaStatus.PLAYER_STATE_BUFFERING -> "Cargando…"
            MediaStatus.PLAYER_STATE_IDLE -> when (status.idleReason) {
                MediaStatus.IDLE_REASON_FINISHED -> "Terminó"
                MediaStatus.IDLE_REASON_ERROR -> "Error al reproducir"
                else -> "Detenido"
            }
            else -> ""
        }
        events.onStatus(state, status.playerState == MediaStatus.PLAYER_STATE_PLAYING, failed, finished)
    }

    private fun textStyle(scale: Float) = TextTrackStyle().apply {
        fontScale = scale
        foregroundColor = 0xFFFFFFFF.toInt()
        backgroundColor = 0x80000000.toInt()
        edgeType = TextTrackStyle.EDGE_TYPE_DROP_SHADOW
        edgeColor = 0xFF000000.toInt()
    }
}
