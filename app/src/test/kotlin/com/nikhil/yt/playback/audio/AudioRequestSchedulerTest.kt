package com.nikhil.yt.playback.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioRequestSchedulerTest {
    @Test fun playbackRunsBeforeQueuedDownloadsWithoutParallelExtraction() = runTest {
        val scheduler = AudioRequestScheduler()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { scheduler.run(priority = AudioRequestPriority.DOWNLOAD) { release.await(); order += "active" } }
        runCurrent()
        launch { scheduler.run(priority = AudioRequestPriority.DOWNLOAD) { order += "queued" } }
        launch { scheduler.run("song", AudioRequestPriority.PLAYBACK) { order += "playing" } }
        runCurrent()
        assertTrue(order.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("active", "playing", "queued"), order)
    }

    @Test fun cancelledWaitersDoNotRunAndSelectedPrefetchIsPromoted() = runTest {
        val scheduler = AudioRequestScheduler()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val owner = launch { scheduler.run { release.await() } }
        runCurrent()
        val obsolete = launch { scheduler.run { fail("Cancelled request started") } }
        launch { scheduler.run("other", AudioRequestPriority.PREFETCH) { order += "other" } }
        launch { scheduler.run("selected", AudioRequestPriority.PREFETCH) { order += "selected" } }
        runCurrent()
        obsolete.cancel()
        scheduler.select("selected")
        owner.cancel()
        runCurrent()
        assertEquals(listOf("selected", "other"), order)
    }
}
