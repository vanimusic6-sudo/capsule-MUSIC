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
import java.io.IOException

/** Offline bytes must cross the same exact, bounded CDN ranges as playback. */
@RunWith(RobolectricTestRunner::class)
class DownloadAudioTransportTest {
    @Test fun advertisedChunkSizesAreBoundedBeforeAnyNetworkOpen() {
        assertEquals(AUDIO_CHUNK_BYTES, downloadAudioChunkSizeBytes(0L))
        assertEquals(AUDIO_CHUNK_BYTES, downloadAudioChunkSizeBytes(Long.MAX_VALUE))
        assertEquals(AUDIO_CHUNK_BYTES, downloadAudioChunkSizeBytes(-1L))
        assertEquals(256L * 1024, downloadAudioChunkSizeBytes(256L * 1024))
        assertEquals(4L * 1024 * 1024, downloadAudioChunkSizeBytes(4L * 1024 * 1024))
    }

    @Test fun offlineDownloadPreservesAllBytesAcrossRangeBoundaries() {
        val bytes = ByteArray(130_000) { (it % 251).toByte() }
        val opens = mutableListOf<Pair<Long, Long>>()
        val network = object : DataSource {
            var cursor = 0
            var left = 0
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                cursor = dataSpec.position.toInt()
                left = dataSpec.length.toInt()
                opens += dataSpec.position to dataSpec.length
                return dataSpec.length
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (left <= 0) return C.RESULT_END_OF_INPUT
                val count = minOf(left, length, 32)
                bytes.copyInto(buffer, offset, cursor, cursor + count)
                cursor += count
                left -= count
                return count
            }
            override fun getUri(): Uri? = Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback")
            override fun close() = Unit
        }
        val dataSource = createDownloadAudioTransport(
            upstream = network,
            health = AudioCdnHostHealth(),
            advertisedChunkBytes = 64L * 1024,
            currentNetworkIdentity = { 12L },
            shouldYieldToPlayback = { false },
        )
        val spec = DataSpec.Builder()
            .setUri("https://rr1---sn-test.googlevideo.com/videoplayback")
            .setPosition(0L)
            .setLength(bytes.size.toLong())
            .setKey("capsule:audio:testid00001:140:130000")
            .build()
        try {
            assertEquals(bytes.size.toLong(), dataSource.open(spec))
            val actual = ByteArray(bytes.size)
            var cursor = 0
            while (cursor < actual.size) {
                val n = dataSource.read(actual, cursor, actual.size - cursor)
                if (n == C.RESULT_END_OF_INPUT) break
                assertTrue(n > 0)
                cursor += n
            }
            assertEquals(bytes.size, cursor)
            assertArrayEquals(bytes, actual)
        } finally {
            dataSource.close()
        }
        assertTrue(opens.isNotEmpty())
        assertEquals(0L, opens.first().first)
        assertTrue(opens.size >= 2)
        assertEquals(130_000L, opens.sumOf { it.second })
    }
}
