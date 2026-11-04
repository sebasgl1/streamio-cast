package com.sebasgl1.streamiocast.subtitles

import java.util.Locale

/*
 * Los idiomas llegan en formatos distintos: ExoPlayer usa "es" o "spa", OpenSubtitles usa
 * ISO 639-2 ("spa", "eng", "pob"...) y a veces el código bibliográfico ("fre", "ger").
 * Todo se lleva al código de 2 letras para poder compararlo.
 */

private val EXTRA = mapOf(
    "fre" to "fr", "ger" to "de", "pob" to "pt", "pb" to "pt", "chi" to "zh", "zht" to "zh",
    "zhe" to "zh", "ze" to "zh", "dut" to "nl", "gre" to "el", "rum" to "ro", "cze" to "cs",
    "per" to "fa", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my",
    "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo",
    "wel" to "cy", "scc" to "sr", "scr" to "hr", "spn" to "es", "ea" to "es",
)

private val ISO3_TO_2: Map<String, String> by lazy {
    Locale.getISOLanguages().associateBy { code ->
        runCatching { Locale.forLanguageTag(code).getISO3Language() }.getOrDefault("")
    }
}

private val SPANISH = Locale.forLanguageTag("es")

fun normalizeLang(code: String?): String? {
    if (code.isNullOrBlank()) return null
    val c = code.trim().lowercase().replace('_', '-').substringBefore('-')
    if (c.isEmpty() || c == "und" || c == "unk" || c == "mul" || c == "zxx") return null
    EXTRA[c]?.let { return it }
    if (c.length == 2) return c
    ISO3_TO_2[c]?.let { return it }
    return c
}

/** Nombre en español para mostrar ("Español", "Inglés"...). */
fun languageName(code: String?, original: String? = null): String {
    if (original?.trim()?.lowercase() == "pob") return "Portugués (Brasil)"
    val c = normalizeLang(code) ?: return original?.takeIf { it.isNotBlank() } ?: "Desconocido"
    val name = Locale.forLanguageTag(c).getDisplayLanguage(SPANISH)
    return if (name.isBlank() || name.equals(c, ignoreCase = true)) {
        original?.takeIf { it.isNotBlank() } ?: c
    } else {
        name.replaceFirstChar { it.titlecase(SPANISH) }
    }
}
