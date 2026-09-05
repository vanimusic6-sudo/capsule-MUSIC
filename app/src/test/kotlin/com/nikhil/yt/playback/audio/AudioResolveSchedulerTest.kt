package com.nikhil.yt.playback.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioResolveSchedulerTest {
    @Test fun priorityOrderingIsExplicitAndIndependentOfEnumOrdinal() {
        assertTrue(AudioResolvePriority.PLAYBACK.outranks(AudioResolvePriority.PREFETCH))
        assertTrue(AudioResolvePriority.PREFETCH.outranks(AudioResolvePriority.DOWNLOAD))
        assertFalse(AudioResolvePriority.DOWNLOAD.outranks(AudioResolvePriority.PLAYBACK))
        assertTrue(AudioResolvePriority.entries.zipWithNext().all { (left, right) ->
            left.schedulingRank < right.schedulingRank
        })
    }

    @Test fun playbackOfTheDownloadingSongStillGetsForegroundPriority() = runTest {
        val scheduler = AudioResolveScheduler()
        val events = mutableListOf<String>()
        val download = async {
            scheduler.run("track", AudioResolvePriority.DOWNLOAD) {
                delay(1_000)
                events += "download"
            }
        }
        runCurrent()
        // MusicService promotes its shared prefetch before joining it. A download
        // with the same id is a separate job and must not be promoted by this call.
        scheduler.promote("track")
        val playback = async {
            scheduler.run("track", AudioResolvePriority.PLAYBACK) { events += "playback" }
        }
        advanceUntilIdle()
        playback.await()
        download.await()
        assertEquals(listOf("playback", "download"), events)
    }

    @Test fun playbackPreemptsDownloadAndDownloadResumesWithoutFailing() = runTest {
        val scheduler = AudioResolveScheduler()
        val events = mutableListOf<String>()
        val background = async {
            scheduler.run("download", AudioResolvePriority.DOWNLOAD) {
                events += "download-start"
                delay(1_000)
                events += "download-end"
                42
            }
        }
        runCurrent()
        val foreground = async { scheduler.run("current", AudioResolvePriority.PLAYBACK) { events += "playback" } }
        advanceUntilIdle()
        foreground.await()
        assertEquals(42, background.await())
        assertEquals(listOf("download-start", "playback", "download-start", "download-end"), events)
    }

    @Test fun cancelledQueuedTrackNeverContactsTheTransport() = runTest {
        val scheduler = AudioResolveScheduler()
        val active = launch { scheduler.run("active", AudioResolvePriority.PLAYBACK) { delay(100) } }
        runCurrent()
        var calls = 0
        val stale = launch { scheduler.run("stale", AudioResolvePriority.PREFETCH) { calls++ } }
        runCurrent()
        stale.cancelAndJoin()
        active.join()
        assertEquals(0, calls)
        assertEquals("available", scheduler.run("next", AudioResolvePriority.PLAYBACK) { "available" })
    }

    @Test fun promotingPrefetchKeepsTheSameExtraction() = runTest {
        val scheduler = AudioResolveScheduler()
        var calls = 0
        val prefetch = async { scheduler.run("next", AudioResolvePriority.PREFETCH) { calls++; delay(100); 7 } }
        runCurrent()
        scheduler.promote("next")
        val download = async { scheduler.run("download", AudioResolvePriority.DOWNLOAD) { 3 } }
        assertEquals(7, prefetch.await())
        assertEquals(3, download.await())
        assertEquals(1, calls)
    }

    @Test fun parentCancellationIsNeverTreatedAsPreemption() = runTest {
        val scheduler = AudioResolveScheduler()
        var calls = 0
        val work = launch { scheduler.run("stale", AudioResolvePriority.DOWNLOAD) { calls++; awaitCancellation() } }
        runCurrent()
        work.cancelAndJoin()
        assertEquals(1, calls)
        assertEquals(5, scheduler.run("current", AudioResolvePriority.PLAYBACK) { 5 })
    }

    @Test fun networkTimeoutStartsAfterTheTurnIsGranted() = runTest {
        val scheduler = AudioResolveScheduler()
        val first = launch { scheduler.run("one", AudioResolvePriority.PLAYBACK) { delay(1_000) } }
        runCurrent()
        val second = async {
            scheduler.run("two", AudioResolvePriority.PLAYBACK) { withTimeout(100) { delay(90); 9 } }
        }
        first.join()
        assertEquals(9, second.await())
    }
}
