package com.nikhil.yt.playback.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioResolveSchedulerTest {
    @Test fun downloadStartWindowsUseInjectedVariableSpacing() = runTest {
        val gaps = listOf(3_200L, 5_800L, 4_100L).iterator()
        val scheduler = AudioResolveScheduler(
            monotonicNowMs = { testScheduler.currentTime },
            downloadStartSpacingMs = { gaps.next() },
        )
        val starts = mutableListOf<Long>()
        repeat(3) { index ->
            scheduler.run("download-$index", AudioResolvePriority.DOWNLOAD) {
                starts += testScheduler.currentTime
            }
        }
        assertEquals(listOf(0L, 3_200L, 9_000L), starts)
    }

    @Test fun playbackPreemptsPacingWaitWithoutRedrawingItsDeadline() = runTest {
        var draws = 0
        val scheduler = AudioResolveScheduler(
            monotonicNowMs = { testScheduler.currentTime },
            downloadStartSpacingMs = { draws++; 5_800L },
        )
        scheduler.run("first", AudioResolvePriority.DOWNLOAD) { }
        val starts = mutableListOf<Long>()
        val waiting = async {
            scheduler.run("second", AudioResolvePriority.DOWNLOAD) { starts += testScheduler.currentTime }
        }
        runCurrent()
        advanceTimeBy(1_000)
        val playback = async { scheduler.run("current", AudioResolvePriority.PLAYBACK) { testScheduler.currentTime } }
        runCurrent()
        assertEquals(1_000L, playback.await())
        assertEquals(1, draws)
        advanceUntilIdle()
        waiting.await()
        assertEquals(listOf(5_800L), starts)
        assertEquals(2, draws)
    }

    @Test fun priorityOrderingIsExplicitAndIndependentOfEnumOrdinal() {
        assertEquals(0, AudioResolvePriority.PLAYBACK.schedulingRank)
        assertEquals(100, AudioResolvePriority.PREFETCH.schedulingRank)
        assertEquals(200, AudioResolvePriority.DOWNLOAD.schedulingRank)
        assertTrue(AudioResolvePriority.PLAYBACK.outranks(AudioResolvePriority.PREFETCH))
        assertTrue(AudioResolvePriority.PREFETCH.outranks(AudioResolvePriority.DOWNLOAD))
        assertFalse(AudioResolvePriority.DOWNLOAD.outranks(AudioResolvePriority.PLAYBACK))
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

    @Test fun downloadRestartAfterPreemptionKeepsActualStartSpacing() = runTest {
        val scheduler =
            AudioResolveScheduler(
                monotonicNowMs = { testScheduler.currentTime },
                downloadStartSpacingMs = { 4_000L },
            )
        val starts = mutableListOf<Long>()
        val download = async {
            scheduler.run("download", AudioResolvePriority.DOWNLOAD) {
                starts += testScheduler.currentTime
                delay(10_000)
                42
            }
        }
        runCurrent()
        assertEquals(listOf(0L), starts)

        advanceTimeBy(500)
        val playback = async {
            scheduler.run("current", AudioResolvePriority.PLAYBACK) { 7 }
        }
        runCurrent()
        assertEquals(7, playback.await())

        advanceTimeBy(3_499)
        runCurrent()
        assertEquals(listOf(0L), starts)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 4_000L), starts)
        download.cancelAndJoin()
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

    @Test fun loaderPromotionBeforeTicketPromotesTheNextPrefetchRun() = runTest {
        val scheduler = AudioResolveScheduler(monotonicNowMs = { testScheduler.currentTime })
        scheduler.promote("next")
        assertEquals(
            AudioResolvePriority.PLAYBACK,
            scheduler.effectivePriority("next", AudioResolvePriority.PREFETCH),
        )

        var observed = AudioResolvePriority.PREFETCH
        val value =
            scheduler.run("next", AudioResolvePriority.PREFETCH) {
                observed = scheduler.effectivePriority("next", AudioResolvePriority.PREFETCH)
                7
            }

        assertEquals(7, value)
        assertEquals(AudioResolvePriority.PLAYBACK, observed)
    }

    @Test fun loaderPromotionIsVisibleInsideYoungSharedPrefetch() = runTest {
        val scheduler = AudioResolveScheduler(monotonicNowMs = { testScheduler.currentTime })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observed = mutableListOf<AudioResolvePriority>()

        val work = async {
            scheduler.run("next", AudioResolvePriority.PREFETCH) {
                observed += scheduler.effectivePriority("next", AudioResolvePriority.PREFETCH)
                started.complete(Unit)
                release.await()
                observed += scheduler.effectivePriority("next", AudioResolvePriority.PREFETCH)
            }
        }

        started.await()
        scheduler.promote("next")
        release.complete(Unit)
        work.await()

        assertEquals(
            listOf(AudioResolvePriority.PREFETCH, AudioResolvePriority.PLAYBACK),
            observed,
        )
    }

    @Test fun stalePrefetchPromotionRestartsAsForeground() = runTest {
        val scheduler =
            AudioResolveScheduler(
                monotonicNowMs = { testScheduler.currentTime },
                promotedPrefetchRestartAfterMs = 4_000L,
            )
        var calls = 0
        val prefetch = async {
            scheduler.run("next", AudioResolvePriority.PREFETCH) {
                calls += 1
                if (calls == 1) {
                    delay(10_000)
                    1
                } else {
                    7
                }
            }
        }
        runCurrent()
        assertEquals(1, calls)

        advanceTimeBy(4_000)
        scheduler.promote("next")
        runCurrent()

        assertEquals(7, prefetch.await())
        assertEquals(2, calls)
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
