package com.sebasgl1.streamiocast.data

/*
 * Qué reproduce cada aparato. Sale de la marca y el modelo que el propio aparato informa, más lo
 * que la app aprendió con el botón "No tiene sonido".
 *
 * Datos conocidos:
 *  - Samsung quitó el DTS de sus TV desde los modelos 2018.
 *  - LG quitó el DTS en 2020-2021 (CX, C1…), lo devolvió en 2022-2024 y lo volvió a quitar en 2025.
 *  - Casi ningún TV decodifica TrueHD por sí mismo.
 *  - Samsung no soporta Dolby Vision; LG sí.
 *  - AV1: Samsung y LG desde los modelos 2020.
 *  - Chromecast 2ª gen: H.264 8 bits hasta 1080p. Ultra y Google TV: también HEVC, 10 bits y 4K.
 */

enum class Support { YES, NO, UNKNOWN }

data class TvProfile(
    /** "Samsung 2017", "LG 2021" o "TV" si no se reconoce. */
    val label: String,
    val dts: Support,
    val av1: Support,
    val dolbyVision: Support,
    /** Audios que en este TV sonaron mudos (botón "No tiene sonido"). */
    val muted: Set<AudioCodec> = emptySet(),
) {
    companion object {
        /** Sin TV elegido: no se sabe si suena DTS. */
        val GENERIC = TvProfile("TV", Support.UNKNOWN, Support.UNKNOWN, Support.UNKNOWN)
    }
}

data class CastProfile(
    val label: String,
    /** Chromecast Ultra / con Google TV: HEVC, 10 bits, HDR y 4K. */
    val advanced: Boolean,
    val av1: Boolean,
    val muted: Set<AudioCodec> = emptySet(),
) {
    companion object {
        /** El Chromecast de 2ª generación (el más limitado). */
        val GEN2 = CastProfile("Chromecast", advanced = false, av1 = false)
    }
}

// --- Año del TV a partir del modelo ---------------------------------------------------

/** Letra de año de Samsung (UN43**M**U6100, QN65Q80**R**, QN90**A**). */
private val SAMSUNG_YEARS = mapOf(
    'H' to 2014, 'J' to 2015, 'K' to 2016, 'M' to 2017, 'N' to 2018, 'R' to 2019,
    'T' to 2020, 'A' to 2021, 'B' to 2022, 'C' to 2023, 'D' to 2024, 'F' to 2025,
)
private val R_SAMSUNG_PREFIX = Regex("""^(?:UN|UE|UA|QN|QE|QA|GQ|GU|KQ|KU|HG)?\d{2}""")
private val R_SAMSUNG_LINE = Regex("""^(?:QN|Q|S|LS)\d{2,3}([A-Z])""")
private val R_SAMSUNG_U = Regex("""^U\d{4}([A-Z])""")

/** Año de un Samsung por su modelo: UN43MU6100 → 2017, QN65Q80R → 2019, QE55S90D → 2024. */
fun samsungYear(model: String): Int? {
    val m = model.uppercase().replace(" ", "")
    val rest = R_SAMSUNG_PREFIX.find(m)?.let { m.substring(it.range.last + 1) } ?: return null
    if (rest.isEmpty()) return null
    // QLED / OLED / The Frame: el año va después de la línea (Q80R, QN90A, S90D, LS03B).
    R_SAMSUNG_LINE.find(rest)?.let { match -> SAMSUNG_YEARS[match.groupValues[1][0]]?.let { return it } }
    // Modelos 2025 "U8000F": la letra va al final.
    R_SAMSUNG_U.find(rest)?.let { match -> SAMSUNG_YEARS[match.groupValues[1][0]]?.let { return it } }
    // LED / Crystal UHD: la primera letra (MU, NU, RU, TU, AU, BU, CU, DU).
    return SAMSUNG_YEARS[rest[0]]
}

/** Letra de año de LG en los LED (43UM7300 → M) y número en los OLED (OLED55CX → X, C9 → 9). */
private val LG_LED_YEARS = mapOf(
    'J' to 2017, 'K' to 2018, 'M' to 2019, 'N' to 2020, 'P' to 2021,
    'Q' to 2022, 'R' to 2023, 'T' to 2024, 'A' to 2025,
)
private val LG_OLED_YEARS = mapOf(
    '7' to 2017, '8' to 2018, '9' to 2019, 'X' to 2020, '1' to 2021,
    '2' to 2022, '3' to 2023, '4' to 2024, '5' to 2025,
)
private val R_LG_OLED = Regex("""OLED\d{2}[A-Z]([0-9X])""")
private val R_LG_NANO = Regex("""(?:NANO|QNED)\d{2}U?([A-Z])""")
private val R_LG_LED = Regex("""(?:\d{2}|\b)[US]([A-Z])\d{4}""")

fun lgYear(model: String): Int? {
    val m = model.uppercase()
    R_LG_OLED.find(m.replace(" ", ""))?.let { return LG_OLED_YEARS[it.groupValues[1][0]] }
    R_LG_NANO.find(m)?.let { return LG_LED_YEARS[it.groupValues[1][0]] }
    R_LG_LED.find(m)?.let { return LG_LED_YEARS[it.groupValues[1][0]] }
    return null
}

/** Perfil del TV según lo que informa por DLNA (marca, modelo y nombre). */
fun tvProfileFor(manufacturer: String, model: String, name: String, muted: Set<AudioCodec> = emptySet()): TvProfile {
    val brand = "$manufacturer $name".lowercase()
    val haystack = "$model $name"
    return when {
        "samsung" in brand -> {
            val year = samsungYear(model) ?: samsungYear(name)
            TvProfile(
                label = if (year != null) "Samsung $year" else "Samsung",
                dts = when {
                    year == null -> Support.UNKNOWN
                    year <= 2017 -> Support.YES
                    else -> Support.NO
                },
                av1 = when {
                    year == null -> Support.UNKNOWN
                    year >= 2020 -> Support.YES
                    else -> Support.NO
                },
                dolbyVision = Support.NO,
                muted = muted,
            )
        }

        "lg" in brand.split(' ', '-', '.') || brand.startsWith("lg") -> {
            val year = lgYear(model) ?: lgYear(haystack)
            TvProfile(
                label = if (year != null) "LG $year" else "LG",
                dts = when (year) {
                    null -> Support.UNKNOWN
                    in 2020..2021, in 2025..2100 -> Support.NO
                    else -> Support.YES
                },
                av1 = when {
                    year == null -> Support.UNKNOWN
                    year >= 2020 -> Support.YES
                    else -> Support.NO
                },
                dolbyVision = Support.YES,
                muted = muted,
            )
        }

        else -> TvProfile.GENERIC.copy(
            label = manufacturer.trim().split(' ').firstOrNull()?.takeIf { it.isNotBlank() } ?: "TV",
            muted = muted,
        )
    }
}

/** Perfil del Chromecast según su modelo ("Chromecast", "Chromecast Ultra", "Google TV Streamer"…). */
fun castProfileFor(modelName: String?, muted: Set<AudioCodec> = emptySet()): CastProfile {
    val m = modelName.orEmpty().lowercase()
    return when {
        "streamer" in m -> CastProfile("Google TV Streamer", advanced = true, av1 = true, muted = muted)
        "google tv" in m -> CastProfile("Chromecast con Google TV", advanced = true, av1 = false, muted = muted)
        "ultra" in m -> CastProfile("Chromecast Ultra", advanced = true, av1 = false, muted = muted)
        // Televisores con Chromecast integrado (Android TV / Google TV): suelen decodificar HEVC y 4K.
        m.isNotBlank() && "chromecast" !in m -> CastProfile(modelName!!.trim(), advanced = true, av1 = false, muted = muted)
        else -> CastProfile.GEN2.copy(muted = muted)
    }
}

// --- Audio: lo que decide si suena ------------------------------------------------------

/** Veredicto del audio para un aparato: mudo seguro, dudoso o bien. */
internal data class AudioCheck(val verdict: Verdict, val reason: String?)

/**
 * [playable]: audios que el aparato reproduce seguro. [silent]: que seguro no (o que ya sonaron
 * mudos). El resto, dudoso. El TV usa la primera pista del archivo y el nombre no dice el orden:
 * si trae una buena y una mala, queda dudoso.
 */
internal fun checkAudio(
    audio: Set<AudioCodec>,
    playable: Set<AudioCodec>,
    silent: Set<AudioCodec>,
    device: String,
): AudioCheck {
    if (audio.isEmpty()) return AudioCheck(Verdict.GOOD, null)
    val bad = audio.filter { it in silent }
    val good = audio.filter { it in playable }
    val names = { list: Collection<AudioCodec> -> list.joinToString("/") { it.label } }
    return when {
        bad.isNotEmpty() && good.isEmpty() && bad.size == audio.size -> AudioCheck(
            Verdict.BAD,
            "Audio ${names(bad)}: en $device suena mudo.",
        )
        bad.isNotEmpty() -> AudioCheck(
            Verdict.MAYBE,
            "Trae audio ${names(audio)}. $device no reproduce ${names(bad)} y suele usar la primera pista: " +
                "puede sonar mudo.",
        )
        good.size == audio.size -> AudioCheck(Verdict.GOOD, null)
        good.isNotEmpty() -> AudioCheck(
            Verdict.MAYBE,
            "Trae audio ${names(audio)}. No es seguro que $device reproduzca " +
                "${names(audio.filter { it !in playable })}; si suena mudo, avísame con \"No tiene sonido\".",
        )
        else -> AudioCheck(
            Verdict.MAYBE,
            "Audio ${names(audio)}: no es seguro que $device lo reproduzca con sonido.",
        )
    }
}

/** Audios que el TV reproduce y los que no, según su perfil. */
internal fun TvProfile.playableAudio(): Set<AudioCodec> = buildSet {
    addAll(listOf(AudioCodec.AAC, AudioCodec.MP3, AudioCodec.AC3, AudioCodec.EAC3))
    if (dts == Support.YES) add(AudioCodec.DTS)
    removeAll(muted)
}

internal fun TvProfile.silentAudio(): Set<AudioCodec> = buildSet {
    addAll(muted)
    if (dts == Support.NO) add(AudioCodec.DTS)
    add(AudioCodec.TRUEHD)
}

internal fun CastProfile.playableAudio(): Set<AudioCodec> = buildSet {
    addAll(listOf(AudioCodec.AAC, AudioCodec.MP3))
    if (advanced) addAll(listOf(AudioCodec.AC3, AudioCodec.EAC3))
    removeAll(muted)
}

internal fun CastProfile.silentAudio(): Set<AudioCodec> = buildSet {
    addAll(muted)
    add(AudioCodec.TRUEHD)
}

/** Códec de audio a partir del tipo MIME real (lo que lee el reproductor del archivo). */
fun audioCodecOfMime(mime: String?): AudioCodec? = when (mime) {
    "audio/mp4a-latm" -> AudioCodec.AAC
    "audio/mpeg" -> AudioCodec.MP3
    "audio/opus" -> AudioCodec.OPUS
    "audio/flac" -> AudioCodec.FLAC
    "audio/ac3" -> AudioCodec.AC3
    "audio/eac3", "audio/eac3-joc" -> AudioCodec.EAC3
    "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd" -> AudioCodec.DTS
    "audio/true-hd" -> AudioCodec.TRUEHD
    else -> null
}
