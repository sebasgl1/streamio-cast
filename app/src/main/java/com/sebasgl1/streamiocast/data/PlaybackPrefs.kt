package com.sebasgl1.streamiocast.data

import android.content.Context

/** Preferencias de reproducción: idioma de audio, de subtítulos y tamaño de letra. */
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

    companion object {
        const val ORIGINAL = "original"
        const val OFF = "off"
    }
}
