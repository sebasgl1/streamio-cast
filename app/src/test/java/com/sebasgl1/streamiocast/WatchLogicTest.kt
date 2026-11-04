package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.history.WatchEntry
import com.sebasgl1.streamiocast.history.WatchTarget
import com.sebasgl1.streamiocast.history.continueWatching
import com.sebasgl1.streamiocast.history.formatClock
import com.sebasgl1.streamiocast.history.isWatched
import com.sebasgl1.streamiocast.history.remainingLabel
import com.sebasgl1.streamiocast.history.resumePosition
import com.sebasgl1.streamiocast.history.seasonEpisodeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLogicTest {

    private fun target(video: String, meta: String = video) =
        WatchTarget(videoId = video, metaId = meta, type = "movie", name = "Peli $video")

    private fun entry(video: String, pos: Long, dur: Long, at: Long, meta: String = video, watched: Boolean = false, hidden: Boolean = false) =
        WatchEntry(target(video, meta), positionMs = pos, durationMs = dur, watched = watched, updatedAt = at, hidden = hidden)

    @Test
    fun watchedThreshold() {
        assertFalse(isWatched(90_000, 100_000))
        assertTrue(isWatched(92_000, 100_000))
        assertFalse(isWatched(50_000, 0))
    }

    @Test
    fun resumeRules() {
        assertNull(resumePosition(null))
        assertNull(resumePosition(entry("a", 10_000, 7_200_000, 1))) // menos de 30 s
        assertEquals(2_500_000L, resumePosition(entry("a", 2_500_000, 7_200_000, 1)))
        assertNull(resumePosition(entry("a", 7_000_000, 7_200_000, 1))) // en los créditos
        assertNull(resumePosition(entry("a", 2_500_000, 7_200_000, 1, watched = true)))
        assertEquals(60_000L, resumePosition(entry("a", 60_000, 0, 1))) // duración aún desconocida
    }

    @Test
    fun continueWatchingRow() {
        val list = continueWatching(
            listOf(
                entry("m1", 600_000, 6_000_000, at = 10),
                entry("s:1:1", 300_000, 2_400_000, at = 20, meta = "s"),
                entry("s:1:2", 400_000, 2_400_000, at = 30, meta = "s"), // el más reciente de la serie
                entry("m2", 5_900_000, 6_000_000, at = 40), // casi terminada → visto
                entry("m3", 600_000, 6_000_000, at = 50, hidden = true),
                entry("m4", 5_000, 6_000_000, at = 60), // apenas empezada
            ),
        )
        assertEquals(listOf("s:1:2", "m1"), list.map { it.target.videoId })
    }

    @Test
    fun upNextEpisodeShowsInContinueWatching() {
        val next = entry("s:1:3", 1, 0, at = 99, meta = "s").copy(upNext = true)
        val list = continueWatching(listOf(entry("s:1:2", 2_300_000, 2_400_000, at = 98, meta = "s", watched = true), next))
        assertEquals(listOf("s:1:3"), list.map { it.target.videoId })
        assertEquals("Siguiente", remainingLabel(next))
        assertEquals(1 to 3, seasonEpisodeOf("tt0944947:1:3"))
        assertNull(seasonEpisodeOf("kitsu:123:4"))
        assertNull(seasonEpisodeOf("tt0133093"))
    }

    @Test
    fun labels() {
        assertEquals("41:23", formatClock(2_483_000))
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("quedan 30 min", remainingLabel(entry("a", 1_800_000, 3_600_000, 1)))
        assertNull(remainingLabel(entry("a", 1_800_000, 0, 1)))
    }
}
