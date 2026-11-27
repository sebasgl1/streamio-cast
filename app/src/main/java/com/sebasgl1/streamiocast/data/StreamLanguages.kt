package com.sebasgl1.streamiocast.data

import java.text.Normalizer

/*
 * Idiomas de un stream, leídos de lo que muestra el addon: Torrentio pone banderas (🇪🇸 🇲🇽 🇫🇷…)
 * y los nombres de archivo suelen decir "Latino", "Castellano", "Dual", "Multi"… Torrentio no
 * marca el inglés: lo da por hecho.
 *
 * Códigos: es-la (latino), es-es (castellano), es (español sin especificar), en, fr, it, de, pt,
 * ru, ja, ko, zh, hi, pl, nl, tr, uk y "multi" (varios idiomas sin decir cuáles).
 */

private val FLAG_LANG = mapOf(
    "MX" to "es-la", "AR" to "es-la", "CO" to "es-la", "CL" to "es-la", "PE" to "es-la", "VE" to "es-la",
    "ES" to "es-es",
    "GB" to "en", "US" to "en",
    "FR" to "fr", "IT" to "it", "DE" to "de", "PT" to "pt", "BR" to "pt", "RU" to "ru",
    "JP" to "ja", "KR" to "ko", "CN" to "zh", "TW" to "zh", "IN" to "hi",
    "PL" to "pl", "NL" to "nl", "TR" to "tr", "UA" to "uk",
)

private val WORD_LANG = listOf(
    Regex("""\b(latino|latinoamericano|latam|esp[ -]?lat|lat)\b""") to "es-la",
    Regex("""\b(castellano|castilian|spain)\b""") to "es-es",
    Regex("""\b(spanish|espanol|spa|esp)\b""") to "es",
    Regex("""\b(english|eng)\b""") to "en",
    Regex("""\b(french|francais|fre|vff|vfq|truefrench)\b""") to "fr",
    Regex("""\b(italian|italiano|ita)\b""") to "it",
    Regex("""\b(german|deutsch|ger)\b""") to "de",
    Regex("""\b(portuguese|portugues|dublado|por)\b""") to "pt",
    Regex("""\b(russian|rus)\b""") to "ru",
    Regex("""\b(japanese|jpn|jap)\b""") to "ja",
    Regex("""\b(korean|kor)\b""") to "ko",
    Regex("""\b(hindi|hin)\b""") to "hi",
    Regex("""\b(multi|multi[ -]?audio|dual[ -]?audio|dual)\b""") to "multi",
)

/** Quita tildes y pasa a minúsculas: "Español" y "espanol" se buscan igual. */
fun foldText(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").lowercase()

fun detectLanguages(text: String): Set<String> {
    val found = linkedSetOf<String>()
    // Banderas: dos "letras regionales" seguidas (🇲 + 🇽 = MX).
    val cps = text.codePoints().toArray()
    var i = 0
    while (i < cps.size - 1) {
        val a = cps[i]
        val b = cps[i + 1]
        if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
            val code = "${'A' + (a - 0x1F1E6)}${'A' + (b - 0x1F1E6)}"
            FLAG_LANG[code]?.let { found += it }
            i += 2
        } else {
            i++
        }
    }
    val hay = foldText(text).replace('.', ' ').replace('_', ' ')
    for ((regex, lang) in WORD_LANG) if (regex.containsMatchIn(hay)) found += lang
    // "Español" genérico sobra si ya se sabe si es latino o castellano.
    if ("es" in found && ("es-la" in found || "es-es" in found)) found -= "es"
    return found
}

/** Idioma para el filtro (latino y castellano cuentan como español). */
fun languageFamily(code: String): String = code.substringBefore('-')

val StreamInfo.languageFamilies: Set<String>
    get() = languages.map { languageFamily(it) }.toSet()

fun languageLabel(code: String): String = when (code) {
    "es-la" -> "Latino"
    "es-es" -> "Castellano"
    "es" -> "Español"
    "en" -> "Inglés"
    "fr" -> "Francés"
    "it" -> "Italiano"
    "de" -> "Alemán"
    "pt" -> "Portugués"
    "ru" -> "Ruso"
    "ja" -> "Japonés"
    "ko" -> "Coreano"
    "zh" -> "Chino"
    "hi" -> "Hindi"
    "pl" -> "Polaco"
    "nl" -> "Neerlandés"
    "tr" -> "Turco"
    "uk" -> "Ucraniano"
    "multi" -> "Varios idiomas"
    else -> code
}

/**
 * Búsqueda libre en la lista: todas las palabras deben aparecer en el nombre del stream o en sus
 * idiomas ("latino 1080", "castellano dual"…). Sin importar tildes ni mayúsculas.
 */
fun StreamResult.matchesQuery(query: String): Boolean {
    val words = foldText(query).split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val hay = foldText(
        listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename, info.releaseName)
            .joinToString(" ") + " " +
            // "Latino" también se encuentra buscando "español".
            (info.languages + info.languageFamilies).joinToString(" ") { languageLabel(it) },
    )
    return words.all { it in hay }
}
