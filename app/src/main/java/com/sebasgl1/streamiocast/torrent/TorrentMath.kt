package com.sebasgl1.streamiocast.torrent

import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/** Lógica pura (sin libtorrent) para poder probarla con tests de JVM. */

private val VIDEO_EXTS = setOf("mkv", "mp4", "m4v", "avi", "webm", "mov", "ts", "m2ts", "wmv", "mpg", "mpeg")

fun isVideoFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS

/**
 * Elige el archivo a reproducir dentro del torrent: el que indica el addon (fileIdx) si es un
 * vídeo; si no, el que coincide con el nombre sugerido; y si no, el vídeo más grande.
 */
fun pickFile(names: List<String>, sizes: List<Long>, fileIdx: Int?, filename: String?): Int? {
    if (fileIdx != null && fileIdx in names.indices && isVideoFile(names[fileIdx])) return fileIdx
    if (filename != null) {
        val byName = names.indexOfFirst { it.equals(filename, ignoreCase = true) }
        if (byName >= 0 && isVideoFile(names[byName])) return byName
    }
    return names.indices.filter { isVideoFile(names[it]) }.maxByOrNull { sizes[it] }
}

/** Ubicación de un archivo dentro de las piezas del torrent. */
data class PieceSpan(val fileOffset: Long, val fileLength: Long, val pieceLength: Int) {
    fun pieceAt(position: Long): Int = ((fileOffset + position) / pieceLength).toInt()

    val firstPiece: Int
        get() = pieceAt(0)

    val lastPiece: Int
        get() = pieceAt(max(0L, fileLength - 1))

    /** Bytes del archivo que hay desde [position] hasta el final de su pieza. */
    fun bytesLeftInPiece(position: Long): Long {
        val absolute = fileOffset + position
        val pieceEnd = (absolute / pieceLength + 1) * pieceLength
        return min(pieceEnd - absolute, fileLength - position)
    }
}

private val DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
)

fun buildMagnet(infoHash: String, trackers: List<String>): String {
    val all = (trackers + DEFAULT_TRACKERS)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(25)
    return buildString {
        append("magnet:?xt=urn:btih:").append(infoHash.lowercase())
        all.forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
    }
}
