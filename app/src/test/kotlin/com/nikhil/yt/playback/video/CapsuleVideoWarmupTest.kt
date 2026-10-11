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

    @Test(expected = IllegalArgumentException::class)
    fun refusingOversizedWarmupProtectsVideoCdn() = runBlocking {
        warmupVideoPrefix(
            DataSource.Factory { error("must not open") },
            "https://cdn.example.test/",
            "video",
            20L * 1024L * 1024L,
        )
    }
}
