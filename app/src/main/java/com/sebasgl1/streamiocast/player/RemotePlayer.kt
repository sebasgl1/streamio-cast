package com.sebasgl1.streamiocast.player

/** Un reproductor remoto: un Chromecast o un televisor DLNA. La sesión los maneja igual. */
interface RemotePlayer {
    val isConnected: Boolean
    val positionMs: Long

    /** ¿Puede cambiar de pista de subtítulos sin recargar el vídeo? (Chromecast sí, DLNA no). */
    val supportsTrackSwitch: Boolean

    fun load(media: CastMedia)
    fun togglePlayback()
    fun seek(positionMs: Long)
    fun setSubtitleScale(scale: Float)
    fun activateTrack(id: Long)
    fun hideSubtitles()

    /** Detiene el vídeo en el televisor sin desconectarse. */
    fun stopMedia()

    /** Deja de transmitir (avisa con onDisconnected). */
    fun disconnect()
    fun release()
}
