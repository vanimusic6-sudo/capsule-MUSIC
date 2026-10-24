package com.nikhil.yt.playback

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DownloadQueueTest {
    @Test fun realMedia3ManagerStartsOneFileAtATimeInOrderAndCancellationReleasesTheNext() {
        val context = RuntimeEnvironment.getApplication()
        val provider = StandaloneDatabaseProvider(context)
        val started = Collections.synchronizedList(mutableListOf<String>())
        val gates = (1..3).associate { "track-$it" to CountDownLatch(1) }
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val index = DefaultDownloadIndex(provider)
        val manager = DownloadManager(context, index, DownloaderFactory { request ->
            object : Downloader {
                override fun download(listener: Downloader.ProgressListener?) {
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    started.add(request.id)
                    try { gates.getValue(request.id).await() } finally { active.decrementAndGet() }
                }
                override fun cancel() { gates.getValue(request.id).countDown() }
                override fun remove() {}
            }
        })
        try {
            manager.maxParallelDownloads = DownloadUtil.MAX_PARALLEL_DOWNLOADS
            manager.setRequirements(Requirements(0))
            manager.resumeDownloads()
            await { manager.isInitialized }
            (1..3).forEach { manager.addDownload(DownloadRequest.Builder("track-$it", Uri.parse("track-$it")).build()) }
            await { started.size == 1 }
            assertEquals(listOf("track-1"), started.toList())
            gates.getValue("track-1").countDown()
            await { started.size == 2 }
            assertEquals(listOf("track-1", "track-2"), started.toList())
            stopCollectionDownloads(manager, listOf("track-1", "track-2"))
            await { started.size == 3 }
            assertEquals(listOf("track-1", "track-2", "track-3"), started.toList())
            assertEquals(1, maximum.get())
            await { index.getDownload("track-2")?.state == androidx.media3.exoplayer.offline.Download.STATE_STOPPED }
            assertEquals(androidx.media3.exoplayer.offline.Download.STATE_COMPLETED, index.getDownload("track-1")?.state)
        } finally {
            gates.values.forEach { it.countDown() }
            manager.release()
            provider.close()
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Media3 queue did not reach the expected state", condition())
    }
}
