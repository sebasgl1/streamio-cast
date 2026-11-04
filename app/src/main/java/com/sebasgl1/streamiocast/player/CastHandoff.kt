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

/** Lo que el Chromecast debe reproducir. */
data class CastMedia(
    val url: String,
    val contentType: String,
    val title: String,
    val startAtMs: Long,
    val subtitleUrl: String?,
    val subtitleLang: String?,
    val subtitleScale: Float,
)

/**
 * Envoltorio del Cast SDK: conecta/desconecta, carga el vídeo en el receptor por defecto de Google
 * y reenvía su estado. Todo se llama desde el hilo principal.
 */
class CastHandoff(context: Context, private val events: Events) {

    interface Events {
        fun onConnected(device: String)
        fun onDisconnected(lastPositionMs: Long)
        fun onStatus(state: String, playing: Boolean, failed: Boolean)
        fun onProgress(positionMs: Long, durationMs: Long)
        fun onConnectFailed()
    }

    private val castContext: CastContext? = runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    private var session: CastSession? = null
    private var lastPositionMs = 0L

    val isConnected: Boolean
        get() = session?.isConnected == true

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

    fun load(media: CastMedia) {
        val remote = session?.remoteMediaClient ?: return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
        }
        val builder = MediaInfo.Builder(media.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.contentType)
            .setMetadata(metadata)
            .setTextTrackStyle(textStyle(media.subtitleScale))
        if (media.subtitleUrl != null) {
            val track = MediaTrack.Builder(SUBTITLE_TRACK_ID, MediaTrack.TYPE_TEXT)
                .setName("Subtítulos")
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(media.subtitleUrl)
                .setContentType("text/vtt")
                .setLanguage(media.subtitleLang ?: "es")
                .build()
            builder.setMediaTracks(listOf(track))
        }
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(builder.build())
            .setAutoplay(true)
            .setCurrentTime(media.startAtMs)
            .apply { if (media.subtitleUrl != null) setActiveTrackIds(longArrayOf(SUBTITLE_TRACK_ID)) }
            .build()
        lastPositionMs = media.startAtMs
        remote.load(request)
    }

    val positionMs: Long
        get() = session?.remoteMediaClient?.approximateStreamPosition?.takeIf { it > 0 } ?: lastPositionMs

    fun togglePlayback() {
        session?.remoteMediaClient?.togglePlayback()
    }

    fun seek(positionMs: Long) {
        session?.remoteMediaClient?.seek(MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0)).build())
    }

    fun setSubtitleScale(scale: Float) {
        session?.remoteMediaClient?.setTextTrackStyle(textStyle(scale))
    }

    fun hideSubtitles() {
        session?.remoteMediaClient?.setActiveMediaTracks(longArrayOf())
    }

    /** Detiene el vídeo en el televisor (sin desconectar el Chromecast). */
    fun stopMedia() {
        session?.remoteMediaClient?.stop()
    }

    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun release() {
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        session?.remoteMediaClient?.let { remote ->
            remote.unregisterCallback(remoteCallback)
            remote.removeProgressListener(progressListener)
        }
        session = null
    }

    private fun publishStatus() {
        val status = session?.remoteMediaClient?.mediaStatus ?: return
        val failed = status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            status.idleReason == MediaStatus.IDLE_REASON_ERROR
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
        events.onStatus(state, status.playerState == MediaStatus.PLAYER_STATE_PLAYING, failed)
    }

    private fun textStyle(scale: Float) = TextTrackStyle().apply {
        fontScale = scale
        foregroundColor = 0xFFFFFFFF.toInt()
        backgroundColor = 0x80000000.toInt()
        edgeType = TextTrackStyle.EDGE_TYPE_DROP_SHADOW
        edgeColor = 0xFF000000.toInt()
    }

    companion object {
        const val SUBTITLE_TRACK_ID = 1L
    }
}
