@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.audio

import com.nikhil.yt.playback.transport.ProgressiveRangeSource
import com.nikhil.yt.playback.transport.RangePlan
import com.nikhil.yt.playback.transport.RangeSizePolicy

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProgressiveRangeSourceTest {
    private val request = DataSpec.Builder().setUri("https://media.example/audio").setLength(12).build()
    private class Server(val response: (DataSpec) -> Map<String, List<String>>) : DataSource {
        val starts = mutableListOf<Long>()
        private lateinit var spec: DataSpec
        private var delivered = 0
        override fun open(dataSpec: DataSpec): Long {
            spec = dataSpec
            starts += spec.position
            delivered = 0
            return spec.length
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (delivered >= spec.length) return C.RESULT_END_OF_INPUT
            val n = minOf(length, spec.length.toInt() - delivered)
            repeat(n) { buffer[offset + it] = (spec.position + delivered + it).toByte() }
            delivered += n
            return n
        }
        override fun getResponseHeaders() = response(spec)
        override fun getUri(): Uri = spec.uri
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun close() = Unit
    }

    private fun reader(server: Server) = ProgressiveRangeSource(
        server, planFor = { RangePlan(8, RangeSizePolicy(8)) }, readAheadFor = { 8 },
        canResume = { _, _ -> false },
    )

    @Test fun shorterConfirmedSlicesStillProduceEveryByteOnce() {
        val server = Server { spec ->
            val end = minOf(spec.position + 2, 11)
            mapOf("Content-Range" to listOf("bytes ${spec.position}-$end/12"))
        }
        val source = reader(server)
        try {
            assertEquals(12L, source.open(request))
            val bytes = ByteArray(12)
            for (i in bytes.indices) assertEquals(1, source.read(bytes, i, 1))
            assertArrayEquals(ByteArray(12) { it.toByte() }, bytes)
            assertEquals(listOf(0L, 3L, 6L, 9L), server.starts)
        } finally { source.close() }
    }

    @Test fun changedSizeOffsetValidatorOrMalformedRangeStopsBeforeSecondSlice() {
        val first = mapOf("Content-Range" to listOf("bytes 0-7/12"), "ETag" to listOf("\"v1\""))
        val next = mapOf("Content-Range" to listOf("bytes 8-11/12"), "ETag" to listOf("\"v1\""))
        for (bad in listOf(
            next + ("Content-Range" to listOf("bytes 8-11/13")),
            next + ("Content-Range" to listOf("bytes 0-3/12")),
            next + ("Content-Range" to listOf("bytes 8-99/12")),
            next + ("Content-Range" to listOf("bytes 8-11/12", "bytes 8-11/12")),
            next + ("ETag" to listOf("\"v2\"")), emptyMap(),
        )) {
            val server = Server { if (it.position == 0L) first else bad }
            val source = reader(server)
            try {
                source.open(request)
                assertEquals(8, source.read(ByteArray(8), 0, 8))
                assertThrows(IOException::class.java) { source.read(ByteArray(4), 0, 4) }
                assertEquals(listOf(0L, 8L), server.starts)
            } finally { source.close() }
        }
    }
}
