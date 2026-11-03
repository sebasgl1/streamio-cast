package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.stream.ByteRange
import com.sebasgl1.streamiocast.stream.RangeResult
import com.sebasgl1.streamiocast.stream.mimeTypeFor
import com.sebasgl1.streamiocast.stream.parseRange
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpRangeTest {

    @Test
    fun noHeaderMeansFull() {
        assertEquals(RangeResult.Full, parseRange(null, 1000))
        assertEquals(RangeResult.Full, parseRange("items=0-10", 1000))
    }

    @Test
    fun closedRange() {
        assertEquals(RangeResult.Partial(ByteRange(100, 199)), parseRange("bytes=100-199", 1000))
    }

    @Test
    fun openRangeGoesToTheEnd() {
        assertEquals(RangeResult.Partial(ByteRange(500, 999)), parseRange("bytes=500-", 1000))
    }

    @Test
    fun endIsClampedToLength() {
        assertEquals(RangeResult.Partial(ByteRange(900, 999)), parseRange("bytes=900-5000", 1000))
    }

    @Test
    fun suffixRange() {
        assertEquals(RangeResult.Partial(ByteRange(990, 999)), parseRange("bytes=-10", 1000))
        assertEquals(RangeResult.Partial(ByteRange(0, 999)), parseRange("bytes=-5000", 1000))
    }

    @Test
    fun onlyFirstOfMultipleRanges() {
        assertEquals(RangeResult.Partial(ByteRange(0, 9)), parseRange("bytes=0-9, 20-29", 1000))
    }

    @Test
    fun outOfBoundsIsUnsatisfiable() {
        assertEquals(RangeResult.Unsatisfiable, parseRange("bytes=1000-", 1000))
        assertEquals(RangeResult.Unsatisfiable, parseRange("bytes=50-10", 1000))
    }

    @Test
    fun rangeLength() {
        assertEquals(100L, ByteRange(100, 199).length)
    }

    @Test
    fun mimeTypes() {
        assertEquals("video/x-matroska", mimeTypeFor("Movie.2020.1080p.MKV"))
        assertEquals("video/mp4", mimeTypeFor("a.mp4"))
        assertEquals("application/octet-stream", mimeTypeFor("readme"))
    }
}
