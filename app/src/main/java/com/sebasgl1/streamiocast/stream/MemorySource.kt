package com.sebasgl1.streamiocast.stream

import kotlin.math.min

/** Un recurso pequeño en memoria (por ejemplo, los subtítulos WebVTT para el Chromecast). */
class MemorySource(private val data: ByteArray, override val mimeType: String) : StreamSource {
    override val length: Long
        get() = data.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int, isCancelled: () -> Boolean): Int {
        if (position >= length) return -1
        val n = min(len.toLong(), length - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }
}
