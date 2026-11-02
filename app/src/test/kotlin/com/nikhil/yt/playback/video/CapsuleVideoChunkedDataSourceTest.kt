@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CapsuleVideoChunkedDataSourceTest {
    @Test fun defaultLongVideoStartsWithSmallRangeAndKeepsOneContinuousByteStream() {
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val cdn = MemoryCdn(bytes)
        val source = CapsuleVideoChunkedDataSource(cdn)
        try {
            assertEquals(bytes.size.toLong(), source.open(DataSpec(uri)))
            assertArrayEquals(bytes, readAll(source))
            assertEquals(512L * 1024, cdn.requests.first().length)
            assertTrue(cdn.requests.all { it.length in 1..CAPSULE_VIDEO_CHUNK_BYTES })
            assertTrue(cdn.requests.size >= 3)
        } finally { source.close() }
    }

    @Test fun midrangeTimeoutResumesMissingBytesWithoutResolvingAnotherClient() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val cdn = MemoryCdn(bytes)
        var timedOut = false
        var reads = 0
        val upstream = object : DataSource by cdn {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (++reads == 3 && !timedOut) {
                    timedOut = true
                    throw java.io.IOException("wrapped Media3 body error", java.net.SocketTimeoutException("body read timed out"))
                }
                return cdn.read(buffer, offset, length)
            }
        }
        val source = CapsuleVideoChunkedDataSource(upstream, chunkBytes = 1024)
        try {
            source.open(DataSpec(uri))
            assertArrayEquals(bytes, readAll(source))
            assertEquals(600L, cdn.requests[1].position)
            assertEquals(424L, cdn.requests[1].length)
        } finally { source.close() }
    }

    private val uri = Uri.parse("https://cdn.example.test/videoplayback")

    private class MemoryCdn(
        private val data: ByteArray,
        private val supportsRanges: Boolean = true,
    ) : DataSource {
        val requests = mutableListOf<DataSpec>()
        private var cursor = 0
        private var remaining = 0
        private var headers: Map<String, List<String>> = emptyMap()

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            requests += dataSpec
            cursor = dataSpec.position.toInt()
            require(cursor <= data.size)
            val available = data.size - cursor
            remaining =
                if (dataSpec.length == C.LENGTH_UNSET.toLong()) available
                else minOf(available.toLong(), dataSpec.length).toInt()
            val bounded = dataSpec.length != C.LENGTH_UNSET.toLong()
            headers =
                if (bounded && supportsRanges && remaining > 0) {
                    mapOf(
                        "cOnTeNt-RaNgE" to listOf(
                            "bytes $cursor-${cursor + remaining - 1}/${data.size}",
                        ),
                    )
                } else {
                    emptyMap()
                }
            return if (bounded) dataSpec.length else available.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0) return C.RESULT_END_OF_INPUT
            val count = minOf(length, remaining)
            data.copyInto(buffer, offset, cursor, cursor + count)
            cursor += count
            remaining -= count
            return count
        }

        override fun getUri(): Uri? = Uri.parse("https://cdn.example.test/videoplayback")

        override fun getResponseHeaders() = headers

        override fun close() {
            remaining = 0
            headers = emptyMap()
        }
    }

    private fun readAll(source: DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(300)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            if (count > 0) out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    @Test
    fun unknownLengthHourLikeStreamUsesBoundedRequestsAndSeamlessBytes() {
        val bytes = ByteArray(13_500) { (it % 251).toByte() }
        val cdn = MemoryCdn(bytes)
        val source = CapsuleVideoChunkedDataSource(cdn, chunkBytes = 1024)
        try {
            assertEquals(bytes.size.toLong(), source.open(DataSpec(uri)))
            assertArrayEquals(bytes, readAll(source))
        } finally {
            source.close()
        }
        assertTrue(cdn.requests.size > 10)
        assertTrue(cdn.requests.all { it.length >= 1L && it.length <= 1024L })
        assertEquals(0L, cdn.requests.first().position)
        assertEquals(1024L, cdn.requests[1].position)
    }

    @Test
    fun knownLengthSmallVideoKeepsOriginalSingleRequest() {
        val bytes = ByteArray(900) { (it % 127).toByte() }
        val cdn = MemoryCdn(bytes)
        val source = CapsuleVideoChunkedDataSource(cdn, chunkBytes = 1024)
        try {
            assertEquals(900L, source.open(DataSpec.Builder().setUri(uri).setLength(900).build()))
            assertArrayEquals(bytes, readAll(source))
        } finally {
            source.close()
        }
        assertEquals(1, cdn.requests.size)
        assertEquals(900L, cdn.requests.single().length)
    }

    @Test
    fun unknownLengthSeekContinuesFromRequestedByte() {
        val bytes = ByteArray(5_000) { (it % 253).toByte() }
        val cdn = MemoryCdn(bytes)
        val source = CapsuleVideoChunkedDataSource(cdn, chunkBytes = 1024)
        try {
            assertEquals(3777L, source.open(DataSpec.Builder().setUri(uri).setPosition(1223).build()))
            assertArrayEquals(bytes.copyOfRange(1223, bytes.size), readAll(source))
        } finally {
            source.close()
        }
        assertEquals(1223L, cdn.requests.first().position)
        assertEquals(2247L, cdn.requests[1].position)
    }

    @Test
    fun serverWithoutContentRangeFallsBackToUnmodifiedStream() {
        val bytes = ByteArray(7_000) { (it % 123).toByte() }
        val cdn = MemoryCdn(bytes, supportsRanges = false)
        val source = CapsuleVideoChunkedDataSource(cdn, chunkBytes = 1024)
        try {
            assertEquals(bytes.size.toLong(), source.open(DataSpec(uri)))
            assertArrayEquals(bytes, readAll(source))
        } finally {
            source.close()
        }
        assertEquals(2, cdn.requests.size)
        assertEquals(1024L, cdn.requests.first().length)
        assertEquals(C.LENGTH_UNSET.toLong(), cdn.requests.last().length)
    }

    @Test
    fun peerResetResumesOnlyMissingVideoRangeWithoutLostOrDuplicateBytes() {
        val bytes = ByteArray(5_000) { (it % 251).toByte() }
        val cdn = MemoryCdn(bytes)
        var resetOnce = false
        var bytesBeforeReset = 0
        val flaky = object : DataSource by cdn {
            // Java's default DataSource#getResponseHeaders is NOT forwarded
            // automatically by Kotlin delegation. Without this the wrapper
            // sees no Content-Range and enters the unbounded fallback path.
            override fun getResponseHeaders(): Map<String, List<String>> = cdn.responseHeaders
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!resetOnce && bytesBeforeReset >= 600) {
                    resetOnce = true
                    throw java.net.SocketException("Connection reset")
                }
                return cdn.read(buffer, offset, length).also {
                    if (it > 0) bytesBeforeReset += it
                }
            }
        }
        val source = CapsuleVideoChunkedDataSource(flaky, chunkBytes = 1024)
        try {
            assertEquals(bytes.size.toLong(), source.open(DataSpec(uri)))
            assertArrayEquals(bytes, readAll(source))
        } finally {
            source.close()
        }
        assertTrue(resetOnce)
        assertEquals(600L, cdn.requests[1].position)
        assertEquals(424L, cdn.requests[1].length)
        assertEquals(1024L, cdn.requests[2].position)
    }

    @Test
    fun onlySocketResetsAreEligibleForInPlaceVideoResume() {
        assertTrue(isRecoverableVideoSocketReset(java.net.SocketException("Connection reset by peer")))
        assertTrue(isRecoverableVideoSocketReset(java.io.IOException(
            "wrapped", java.net.SocketException("ECONNRESET"))))
        org.junit.Assert.assertFalse(isRecoverableVideoSocketReset(java.net.SocketTimeoutException("Read timed out")))
        org.junit.Assert.assertFalse(isRecoverableVideoSocketReset(java.net.UnknownHostException("bad DNS")))
        org.junit.Assert.assertFalse(isRecoverableVideoSocketReset(java.io.InterruptedIOException("cancelled")))
    }

    @Test fun youtubeRangeRefusalNeverReopensTheEntireVideo() {
        val cdn = MemoryCdn(ByteArray(7_000), supportsRanges = false)
        val source = CapsuleVideoChunkedDataSource(cdn, chunkBytes = 1024)
        try {
            try {
                source.open(DataSpec(Uri.parse("https://rr1.googlevideo.com/videoplayback")))
                org.junit.Assert.fail("A refused YouTube range must be recoverable, not unbounded")
            } catch (_: java.io.IOException) {
                assertEquals(1, cdn.requests.size)
                assertEquals(1024L, cdn.requests.single().length)
            }
        } finally { source.close() }
    }
}
