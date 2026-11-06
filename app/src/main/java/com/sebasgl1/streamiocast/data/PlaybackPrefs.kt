package com.sebasgl1.streamiocast.data

import android.content.Context

/** Preferencias de reproducción: idiomas, tamaño de letra, arranque con buffer y filtro de streams. */
class PlaybackPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    /** Código de 2 letras, o [ORIGINAL] para no forzar ninguno. */
    var audioLang: String
        get() = prefs.getString("audio", "es") ?: "es"
        set(value) = prefs.edit().putString("audio", value).apply()

    /** Código de 2 letras, o [OFF] para no activar subtítulos solos. */
    var subtitleLang: String
        get() = prefs.getString("subs", "es") ?: "es"
        set(value) = prefs.edit().putString("subs", value).apply()

    /** 0 pequeño, 1 normal, 2 grande. */
    var subtitleSize: Int
        get() = prefs.getInt("subsSize", 1)
        set(value) = prefs.edit().putInt("subsSize", value).apply()

    /** Cómo arranca el vídeo: [START_AUTO], [START_BUFFER] o [START_MANUAL]. */
    var startMode: Int
        get() = prefs.getInt("startMode", START_BUFFER)
        set(value) = prefs.edit().putInt("startMode", value).apply()

    /** Segundos de vídeo descargados que se esperan antes de arrancar (y al quedarse sin buffer). */
    var bufferSeconds: Int
        get() = prefs.getInt("bufferSeconds", 20)
        set(value) = prefs.edit().putInt("bufferSeconds", value).apply()

    /** Último filtro de la lista de streams (nombre de StreamFilter). */
    var streamFilter: String
        get() = prefs.getString("streamFilter", "CHROMECAST") ?: "CHROMECAST"
        set(value) = prefs.edit().putString("streamFilter", value).apply()

    companion object {
        const val ORIGINAL = "original"
        const val OFF = "off"
        const val START_AUTO = 0
        const val START_BUFFER = 1
        const val START_MANUAL = 2
    }
}
