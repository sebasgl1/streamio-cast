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

    /**
     * Subtítulos de internet primero: se usan aunque el archivo traiga los suyos (que no se pueden
     * transmitir al TV). Los del archivo quedan de respaldo si en internet no hay de tu idioma.
     */
    var onlineSubtitlesFirst: Boolean
        get() = prefs.getBoolean("onlineSubsFirst", true)
        set(value) = prefs.edit().putBoolean("onlineSubsFirst", value).apply()

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

    /** Último orden de la lista de streams (nombre de StreamSort) y su sentido. */
    var streamSort: String
        get() = prefs.getString("streamSort", "RECOMMENDED") ?: "RECOMMENDED"
        set(value) = prefs.edit().putString("streamSort", value).apply()

    /**
     * Idioma de la lista de streams ("es", "en"… o "ALL"). Sin elegir: el idioma de audio de
     * Ajustes, así en un celular configurado en español salen primero los streams en español.
     */
    var streamLanguage: String
        get() = prefs.getString("streamLanguage", null)
            ?: audioLang.takeIf { it != ORIGINAL && it.isNotBlank() }?.substringBefore('-')
            ?: "ALL"
        set(value) = prefs.edit().putString("streamLanguage", value).apply()

    var streamSortDescending: Boolean
        get() = prefs.getBoolean("streamSortDesc", true)
        set(value) = prefs.edit().putBoolean("streamSortDesc", value).apply()

    companion object {
        const val ORIGINAL = "original"
        const val OFF = "off"
        const val START_AUTO = 0
        const val START_BUFFER = 1
        const val START_MANUAL = 2
    }
}
