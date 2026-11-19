@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CapsuleVideoWarmupTest {
    @Test fun videoWarmupNeverDownloadsAWholeMovie() = runBlocking {
        var requested = 0L
        var received = 0L
        val source = object : DataSource {
            override fun addTransferListener(listener: TransferListener) = Unit
            override fun open(spec: DataSpec): Long {
                assertEquals("capsule:video:clip:18", spec.key)
                requested = spec.length
                return spec.length
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (received >= requested) return C.RESULT_END_OF_INPUT
                val n = minOf(length.toLong(), requested - received).toInt()
                received += n
                return n
            }
            override fun close() = Unit
            override fun getUri(): Uri = Uri.parse("https://cdn.example.test/videoplayback")
            override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        }
        val read = warmupVideoPrefix(
            factory = DataSource.Factory { source },
            url = "https://cdn.example.test/videoplayback",
            cacheKey = "capsule:video:clip:18",
            bytes = CAPSULE_VIDEO_WARMUP_BYTES,
        )
        assertEquals(256L * 1024L, read)
        assertEquals(256L * 1024L, requested)
    }

    @Test fun brokenZeroReadSourceHasBoundedWarmupWork() = runBlocking {
        var reads = 0
        var closed = false
        val source = object : DataSource {
            override fun addTransferListener(listener: TransferListener) = Unit
            override fun open(spec: DataSpec): Long = spec.length
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                return 0
            }
            override fun close() { closed = true }
            override fun getUri(): Uri = Uri.parse("https://cdn.example.test/videoplayback")
            override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        }
        val failure = runCatching {
            warmupVideoPrefix(DataSource.Factory { source },
                "https://cdn.example.test/videoplayback", "video", 64L * 1024L)
        }.exceptionOrNull()
        assertTrue("Never spin indefinitely on zero-byte reads", failure is IOException)
        assertTrue("Abort a warmup with no byte progress", reads in 1..12)
        assertTrue("Release the DataSource even after a stalled warmup", closed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusingOversizedWarmupProtectsVideoCdn() = runBlocking {
        warmupVideoPrefix(
            DataSource.Factory { error("must not open") },
            "https://cdn.example.test/",
            "video",
            20L * 1024L * 1024L,
        )
        // JUnit4 requires a void-returning test method.
        Unit
    }
}
