package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.data.AppJson
import com.sebasgl1.streamiocast.data.ProgressInput
import com.sebasgl1.streamiocast.data.applyProgress
import com.sebasgl1.streamiocast.data.continueWatchingFrom
import com.sebasgl1.streamiocast.data.parseInstantMs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LibrarySyncTest {

    private val now = Instant.parse("2026-11-04T03:00:00Z")

    private fun input(pos: Long, dur: Long = 3_600_000, video: String = "tt1:1:2", watched: Boolean = false, next: String? = null) =
        ProgressInput("tt1", "series", "Serie", "http://p/x.jpg", video, pos, dur, watched, next)

    private fun JsonObject.state() = this["state"]!!.jsonObject
    private fun JsonObject.num(key: String) = this[key]!!.jsonPrimitive.long

    @Test
    fun createsTemporaryItemLikeStremio() {
        val item = applyProgress(null, input(600_000), now)
        assertEquals("tt1", item["_id"]!!.jsonPrimitive.content)
        assertEquals(true, item["removed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, item["temp"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("2026-11-04T03:00:00Z", item["_mtime"]!!.jsonPrimitive.content)
        val s = item.state()
        assertEquals(600_000L, s.num("timeOffset"))
        assertEquals(3_600_000L, s.num("duration"))
        assertEquals("tt1:1:2", s["video_id"]!!.jsonPrimitive.content)
        assertEquals(0L, s.num("timeWatched")) // video nuevo: arranca de cero
    }

    @Test
    fun keepsUnknownFieldsAndLibraryStatus() {
        val existing = AppJson.parseToJsonElement(
            """{"_id":"tt1","name":"Serie","type":"series","poster":null,"posterShape":"poster",
               "removed":false,"temp":false,"_ctime":"2025-01-01T00:00:00Z","_mtime":"2025-01-01T00:00:00Z",
               "state":{"lastWatched":null,"timeWatched":100000,"timeOffset":500000,"overallTimeWatched":900000,
                        "timesWatched":2,"flaggedWatched":0,"duration":3600000,"video_id":"tt1:1:2",
                        "watched":"tt1:1:2:5:eJyrVgcAAP8Afw==","noNotif":true},
               "behaviorHints":{"defaultVideoId":null,"featuredVideoId":null,"hasScheduledVideos":false},
               "campoNuevo":42}""",
        ).jsonObject
        val item = applyProgress(existing, input(800_000), now)
        assertEquals(false, item["removed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, item["temp"]!!.jsonPrimitive.content.toBoolean()) // sigue en la biblioteca
        assertEquals(JsonPrimitive(42), item["campoNuevo"])
        val s = item.state()
        assertEquals("tt1:1:2:5:eJyrVgcAAP8Afw==", s["watched"]!!.jsonPrimitive.content)
        assertEquals(true, s["noNotif"]!!.jsonPrimitive.content.toBoolean())
        // Mismo vídeo: suma lo avanzado (300 s) como hace Stremio.
        assertEquals(400_000L, s.num("timeWatched"))
        assertEquals(1_200_000L, s.num("overallTimeWatched"))
        assertEquals(800_000L, s.num("timeOffset"))
    }

    @Test
    fun finishedEpisodeAdvancesToNext() {
        val first = applyProgress(null, input(3_400_000), now)
        val done = applyProgress(first, input(3_400_000, watched = true, next = "tt1:1:3"), now)
        val s = done.state()
        assertEquals("tt1:1:3", s["video_id"]!!.jsonPrimitive.content)
        assertEquals(1L, s.num("timeOffset"))
        assertEquals(1L, s.num("timesWatched"))
        assertEquals(0L, s.num("flaggedWatched"))
    }

    @Test
    fun finishedMovieLeavesContinueWatching() {
        val movie = ProgressInput("tt9", "movie", "Peli", null, "tt9", 6_800_000, 7_200_000, true, null)
        val item = applyProgress(null, movie, now)
        assertEquals(0L, item.state().num("timeOffset"))
        assertEquals(1L, item.state().num("timesWatched"))
        assertTrue(continueWatchingFrom(listOf(item)).isEmpty())
    }

    @Test
    fun readsContinueWatching() {
        val inProgress = applyProgress(null, input(600_000), now)
        val removedFromLibrary = AppJson.parseToJsonElement(
            """{"_id":"tt2","type":"movie","name":"X","removed":true,"temp":false,
               "_mtime":"2026-01-01T00:00:00Z","state":{"timeOffset":5000,"duration":10}}""",
        ).jsonObject
        val list = continueWatchingFrom(listOf(inProgress, removedFromLibrary))
        assertEquals(1, list.size)
        assertEquals("tt1:1:2", list[0].videoId)
        assertEquals(600_000L, list[0].positionMs)
        assertEquals(now.toEpochMilli(), list[0].updatedAt)
    }

    @Test
    fun instants() {
        assertEquals(1_577_836_800_000L, parseInstantMs("2020-01-01T00:00:00Z"))
        assertEquals(1_577_836_800_500L, parseInstantMs("2020-01-01T00:00:00.500Z"))
        assertEquals(1_577_836_800_000L, parseInstantMs("2020-01-01T00:00:00+00:00"))
        assertEquals(0L, parseInstantMs("nope"))
    }

    @Test
    fun unwatchClearsTheWatchedFlag() {
        val watched = applyProgress(null, input(0, watched = true), now)
        assertEquals(1L, watched.state().num("flaggedWatched"))
        val undone = applyProgress(watched, input(0).copy(unwatch = true), now)
        assertEquals(0L, undone.state().num("flaggedWatched"))
        assertEquals(0L, undone.state().num("timeOffset"))
        assertEquals("tt1:1:2", undone.state()["video_id"]!!.jsonPrimitive.content)
    }
}
