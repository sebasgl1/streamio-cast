package com.sebasgl1.streamiocast.stream

import kotlin.math.max

data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long
        get() = endInclusive - start + 1
}

sealed class RangeResult {
    /** Sin cabecera Range (o una que no entendemos): se envía el archivo completo. */
    data object Full : RangeResult()

    /** El rango pedido está fuera del archivo: 416. */
    data object Unsatisfiable : RangeResult()

    data class Partial(val range: ByteRange) : RangeResult()
}

/**
 * Interpreta una cabecera `Range: bytes=...` para un recurso de [length] bytes.
 * Solo atiende el primer rango (los reproductores nunca piden varios a la vez).
 */
fun parseRange(header: String?, length: Long): RangeResult {
    if (header == null) return RangeResult.Full
    val h = header.trim()
    if (!h.startsWith("bytes=", ignoreCase = true)) return RangeResult.Full

    val spec = h.substring(6).split(',').first().trim()
    val dash = spec.indexOf('-')
    if (dash < 0) return RangeResult.Full
    val a = spec.substring(0, dash).trim()
    val b = spec.substring(dash + 1).trim()

    if (length <= 0) return RangeResult.Unsatisfiable

    if (a.isEmpty()) {
        // Sufijo: "bytes=-500" = los últimos 500 bytes.
        val n = b.toLongOrNull() ?: return RangeResult.Full
        if (n <= 0) return RangeResult.Unsatisfiable
        return RangeResult.Partial(ByteRange(max(0L, length - n), length - 1))
    }

    val start = a.toLongOrNull() ?: return RangeResult.Full
    if (start >= length) return RangeResult.Unsatisfiable
    val end = if (b.isEmpty()) {
        length - 1
    } else {
        (b.toLongOrNull() ?: return RangeResult.Full).coerceAtMost(length - 1)
    }
    if (end < start) return RangeResult.Unsatisfiable
    return RangeResult.Partial(ByteRange(start, end))
}

fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mkv" -> "video/x-matroska"
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts", "m2ts" -> "video/mp2t"
    "wmv" -> "video/x-ms-wmv"
    "mpg", "mpeg" -> "video/mpeg"
    else -> "application/octet-stream"
}
