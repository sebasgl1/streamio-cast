package com.sebasgl1.streamiocast.history

import java.util.Locale

/** Menos de esto no cuenta como "empezado". */
const val MIN_RESUME_MS = 30_000L

/** A partir del 92 % se da por visto (créditos finales). */
const val WATCHED_FRACTION = 0.92

fun isWatched(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs >= durationMs * WATCHED_FRACTION

/** Dónde retomar, o null si hay que empezar desde el principio. */
fun resumePosition(entry: WatchEntry?): Long? {
    if (entry == null || entry.watched) return null
    if (entry.positionMs < MIN_RESUME_MS) return null
    if (isWatched(entry.positionMs, entry.durationMs)) return null
    return entry.positionMs
}

/** Fila "Continuar viendo": lo empezado y sin terminar, lo más reciente primero, uno por serie. */
fun continueWatching(entries: Collection<WatchEntry>, limit: Int = 20): List<WatchEntry> = entries
    .filter { !it.hidden && resumePosition(it) != null }
    .sortedByDescending { it.updatedAt }
    .distinctBy { it.target.metaId }
    .take(limit)

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

/** "quedan 23 min" para las tarjetas. */
fun remainingLabel(entry: WatchEntry): String? {
    if (entry.durationMs <= 0) return null
    val minutes = ((entry.durationMs - entry.positionMs) / 60_000).coerceAtLeast(1)
    return "quedan $minutes min"
}
