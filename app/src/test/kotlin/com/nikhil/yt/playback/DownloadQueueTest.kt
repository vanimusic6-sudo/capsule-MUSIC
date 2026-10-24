package com.nikhil.yt.playback

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
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
    @Test fun twoTransfersContinueWhileManualTrackOvertakesTheLargeCollectionAndCancellationIsIndependent() {
        val provider = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val started = Collections.synchronizedList(mutableListOf<String>())
        val gates = (1..102).associate { "track-$it" to CountDownLatch(1) }
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val index = DefaultDownloadIndex(provider)
        val manager = DownloadManager(RuntimeEnvironment.getApplication(), index, DownloaderFactory { request ->
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
        AudioDownloadQueue(manager)
        try {
            manager.maxParallelDownloads = DownloadUtil.MAX_PARALLEL_DOWNLOADS
            manager.setRequirements(Requirements(0)); manager.resumeDownloads()
            await { manager.isInitialized }
            (1..100).forEach { manager.addDownload(request(it), DOWNLOAD_WAITING_FOR_SLOT) }
            await { started.size == 2 }
            assertEquals(setOf("track-1", "track-2"), started.toSet())
            manager.addDownload(request(101).withManualPriority(), DOWNLOAD_WAITING_FOR_SLOT)
            await { manager.currentDownloads.any { it.request.id == "track-101" } }
            gates.getValue("track-1").countDown()
            await { started.size == 3 }
            assertEquals("track-101", started[2])
            assertFalse("Second file must continue", gates.getValue("track-2").count == 0L)
            stopCollectionDownloads(manager, (1..101).map { "track-$it" })
            await { index.getDownload("track-2")?.state == Download.STATE_STOPPED }
            assertEquals(Download.STATE_COMPLETED, index.getDownload("track-1")?.state)
            assertEquals(Download.STATE_DOWNLOADING, index.getDownload("track-101")?.state)
            gates.getValue("track-101").countDown()
            await { index.getDownload("track-101")?.state == Download.STATE_COMPLETED }
            assertEquals(2, maximum.get())
        } finally {
            gates.values.forEach { it.countDown() }; manager.release(); provider.close()
        }
    }
    @Test fun persistedPriorityIsIdempotentAndManualBurstsDoNotStarveCollections() {
        val background = entry(request(1), 1)
        val manual = (2..6).map { entry(request(it).withManualPriority(), it.toLong()) }
        val waiting = (listOf(background) + manual).toMutableList()
        val order = AudioDownloadOrder()
        assertEquals(listOf("track-2", "track-3", "track-4", "track-1"), (1..4).map {
            order.next(waiting)!!.also(waiting::remove).request.id
        })
        val promoted = request(10).withManualPriority().withManualPriority()
        assertEquals("title-10", promoted.downloadTitle()); assertTrue(promoted.isManualDownload())
        assertFalse(request(11).isManualDownload())
        assertEquals(Download.STATE_QUEUED, entry(promoted, 1).visibleState)
    }
    private fun entry(request: DownloadRequest, time: Long) = Download(request, Download.STATE_STOPPED,
        time, time, -1, DOWNLOAD_WAITING_FOR_SLOT, Download.FAILURE_REASON_NONE)
    private fun request(i: Int) = DownloadRequest.Builder("track-$i", Uri.parse("track-$i"))
        .setData("title-$i".toByteArray()).build()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Media3 queue did not reach the expected state", condition())
    }
}
