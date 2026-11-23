package com.sebasgl1.streamiocast.subtitles

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Un subtítulo en pantalla: desde, hasta (milisegundos) y texto. */
data class SubCue(val startMs: Long, val endMs: Long, val text: String)

private val ARROW_LINE = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")
private val TIME = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
private val HTML_TAG = Regex("""<[^>]*>""")
private val ASS_TAG = Regex("""\{[^}]*\}""")
private val ONLY_DIGITS = Regex("""^\s*\d+\s*$""")

fun parseTimestamp(s: String): Long? {
    val m = TIME.matchEntire(s.trim()) ?: return null
    val h = m.groupValues[1].ifEmpty { "0" }.toLong()
    val min = m.groupValues[2].toLong()
    val sec = m.groupValues[3].toLong()
    val ms = m.groupValues[4].padEnd(3, '0').take(3).toLong()
    return ((h * 60 + min) * 60 + sec) * 1000 + ms
}

private fun cleanCueText(raw: String): String = raw
    .replace("\\N", "\n")
    .replace("\\n", "\n")
    .replace(ASS_TAG, "")
    .replace(HTML_TAG, "")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&nbsp;", " ")
    .lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .joinToString("\n")

/** Lee subtítulos SRT o WebVTT (tolerante con archivos mal formados). */
fun parseSubtitles(raw: String): List<SubCue> {
    val lines = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val cues = mutableListOf<SubCue>()
    var i = 0
    while (i < lines.size) {
        val arrow = ARROW_LINE.find(lines[i])
        if (arrow == null) {
            i++
            continue
        }
        val start = parseTimestamp(arrow.groupValues[1])
        val end = parseTimestamp(arrow.groupValues[2])
        i++
        val body = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() && !ARROW_LINE.containsMatchIn(lines[i])) {
            body += lines[i]
            i++
        }
        // Archivo sin línea en blanco entre bloques: el número del siguiente quedó pegado.
        if (i < lines.size && ARROW_LINE.containsMatchIn(lines[i]) && body.isNotEmpty() && ONLY_DIGITS.matches(body.last())) {
            body.removeAt(body.lastIndex)
        }
        if (start != null && end != null && end > start) {
            val text = cleanCueText(body.joinToString("\n"))
            if (text.isNotEmpty()) cues += SubCue(start, end, text)
        }
    }
    return cues.sortedBy { it.startMs }
}

private fun vttTime(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    val h = t / 3_600_000
    val m = t / 60_000 % 60
    val s = t / 1000 % 60
    val milli = t % 1000
    return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, milli)
}

/** WebVTT: el único formato de subtítulos que acepta el Chromecast (etapa 4). */
fun toWebVtt(cues: List<SubCue>, offsetMs: Long = 0): String = buildString {
    append("WEBVTT\n\n")
    for (cue in cues) {
        val start = cue.startMs + offsetMs
        val end = cue.endMs + offsetMs
        if (end <= 0) continue
        append(vttTime(start)).append(" --> ").append(vttTime(end)).append('\n')
        append(cue.text).append("\n\n")
    }
}

private fun srtTime(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
}

/** SRT: el formato de subtítulos que leen los televisores Samsung por DLNA. */
fun toSrt(cues: List<SubCue>, offsetMs: Long = 0): String = buildString {
    var n = 1
    for (cue in cues) {
        val start = cue.startMs + offsetMs
        val end = cue.endMs + offsetMs
        if (end <= 0) continue
        append(n++).append('\n')
        append(srtTime(start)).append(" --> ").append(srtTime(end)).append('\n')
        append(cue.text).append("\n\n")
    }
}

/**
 * Muchos subtítulos en español vienen en Windows-1252 en vez de UTF-8 (salen tildes rotas).
 * Si el archivo no es UTF-8 válido, se lee como Windows-1252.
 */
fun decodeSubtitleBytes(raw: ByteArray): String {
    // Algunos addons entregan el .srt comprimido (.gz).
    val bytes = if (raw.size >= 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()) {
        runCatching { java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() } }.getOrDefault(raw)
    } else {
        raw
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, Charsets.UTF_16LE).removePrefix("﻿")
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, Charsets.UTF_16BE).removePrefix("﻿")
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }
    return text.removePrefix("﻿")
}

const val HASH_CHUNK = 65_536

/**
 * Hash de OpenSubtitles: tamaño del archivo + suma (en 64 bits, little-endian) de los primeros y
 * los últimos 64 KB. Es lo que usa Stremio para encontrar subtítulos sincronizados con ese release.
 */
fun openSubtitlesHash(size: Long, head: ByteArray, tail: ByteArray): String {
    var hash = size
    for (chunk in listOf(head, tail)) {
        val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        repeat(chunk.size / 8) { hash += buffer.getLong() }
    }
    return String.format(Locale.US, "%016x", hash)
}
