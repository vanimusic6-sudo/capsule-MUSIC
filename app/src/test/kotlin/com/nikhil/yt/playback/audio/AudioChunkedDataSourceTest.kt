@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The seam between chunks has to be invisible, because everything above it assumes one stream.
 *
 * Splitting a long request is what stops googlevideo pacing it into the ground, but a split that
 * loses a byte, repeats one, or mistakes the end of a chunk for the end of the file is worse than
 * the pacing was: it corrupts or truncates playback instead of merely slowing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioChunkedDataSourceTest {
    /** Serves a known pattern and records the bounded requests it was asked for. */
    private class FakeUpstream(private val content: ByteArray) : DataSource {
        val opens = mutableListOf<Pair<Long, Long>>()
        private var position = 0
        private var left = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            opens += dataSpec.position to dataSpec.length
            position = dataSpec.position.toInt()
            left =
                if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    content.size - position
                } else {
                    dataSpec.length.toInt()
                }
            return dataSpec.length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left == 0) return C.RESULT_END_OF_INPUT
            // Deliberately short reads, the way a real socket delivers.
            val count = minOf(length, left, 7)
            content.copyInto(buffer, offset, position, position + count)
            position += count
            left -= count
            return count
        }

        override fun getUri(): Uri? = Uri.EMPTY

        override fun close() = Unit
    }

    private fun content(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun drain(source: DataSource): ByteArray {
        val out = ArrayList<Byte>()
        val buffer = ByteArray(64)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            repeat(read) { out += buffer[it] }
        }
        return out.toByteArray()
    }

    private fun spec(length: Long, position: Long = 0L) =
        DataSpec.Builder()
            .setUri(Uri.parse("https://example.invalid/stream"))
            .setPosition(position)
            .setLength(length)
            .build()

    @Test
    fun aLongStreamArrivesWholeAndInOrder() {
        val bytes = content(1000)
        val upstream = FakeUpstream(bytes)
        val source = AudioChunkedDataSource(upstream, chunkBytes = 128)

        source.open(spec(bytes.size.toLong()))
        val delivered = drain(source)

        assertArrayEquals("the stream was corrupted across a seam", bytes, delivered)
    }

    @Test
    fun itIsSplitIntoBoundedRequestsThatCoverTheWholeStream() {
        val bytes = content(1000)
        val upstream = FakeUpstream(bytes)
        val source = AudioChunkedDataSource(upstream, chunkBytes = 128)

        source.open(spec(bytes.size.toLong()))
        drain(source)

        assertEquals("a long stream must not be fetched in one request", 8, upstream.opens.size)
        var expected = 0L
        upstream.opens.forEach { (position, length) ->
            assertEquals("a chunk started in the wrong place", expected, position)
            assertTrue("a chunk was unbounded", length in 1..128)
            expected += length
        }
        assertEquals("the chunks do not add up to the stream", bytes.size.toLong(), expected)
    }

    /** A resumed stream keeps its offset: chunk boundaries are relative to where it started. */
    @Test
    fun aStreamResumedPartWayThroughStaysAligned() {
        val bytes = content(1000)
        val upstream = FakeUpstream(bytes)
        val source = AudioChunkedDataSource(upstream, chunkBytes = 128)

        source.open(spec(length = 600, position = 400))
        val delivered = drain(source)

        assertArrayEquals(bytes.copyOfRange(400, 1000), delivered)
        assertEquals(400L, upstream.opens.first().first)
    }

    /** A file that fits in one chunk is handed through untouched, which is every ordinary song. */
    @Test
    fun aShortStreamIsNotSplitAtAll() {
        val bytes = content(100)
        val upstream = FakeUpstream(bytes)
        val source = AudioChunkedDataSource(upstream, chunkBytes = 128)

        source.open(spec(bytes.size.toLong()))
        val delivered = drain(source)

        assertArrayEquals(bytes, delivered)
        assertEquals(listOf(0L to 100L), upstream.opens)
    }

    /**
     * A length that is not known yet is never split.
     *
     * Without it, the end of a chunk and the end of the file are the same event, and guessing wrong
     * either truncates the track or asks forever for bytes that do not exist.
     */
    @Test
    fun aStreamOfUnknownLengthIsLeftAlone() {
        val bytes = content(1000)
        val upstream = FakeUpstream(bytes)
        val source = AudioChunkedDataSource(upstream, chunkBytes = 128)

        source.open(spec(C.LENGTH_UNSET.toLong()))
        val delivered = drain(source)

        assertArrayEquals(bytes, delivered)
        assertEquals("an unknown length must go out as one request", 1, upstream.opens.size)
    }

    @Test
    fun theSplittingRuleMatchesTheSourceItGoverns() {
        assertTrue(shouldChunkAudioRequest(AUDIO_CHUNK_BYTES + 1))
        assertTrue("a stream exactly one chunk long needs no second request", !shouldChunkAudioRequest(AUDIO_CHUNK_BYTES))
        assertTrue(!shouldChunkAudioRequest(C.LENGTH_UNSET.toLong()))
    }
}
