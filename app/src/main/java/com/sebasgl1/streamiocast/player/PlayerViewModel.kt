package com.sebasgl1.streamiocast.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sebasgl1.streamiocast.StremioApp
import kotlinx.coroutines.flow.StateFlow

/**
 * La pantalla solo se engancha a la sesión de reproducción, que vive en la app: si Android recrea
 * la pantalla, o pasa al siguiente episodio, el vídeo y el Chromecast siguen.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val stremio = app as StremioApp

    init {
        stremio.obtainSession()
    }

    val session: StateFlow<PlaybackSession?> = stremio.sessionFlow

    /** Salir del reproductor a propósito: aquí sí se cierra todo. */
    fun close() = stremio.closeSession()
}
