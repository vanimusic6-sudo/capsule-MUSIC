package com.nikhil.yt.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStabilityGateTest {
    @Test
    fun rapidSkipsOnlyContactTheFinalCurrentAndUpcomingTracks() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })
        var current = 0
        val requested = mutableListOf<Int>()
        val jobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

        repeat(25) { index ->
            current = index
            gate.onSelectionChanged()
            // The next track's job may become the loader's existing shared job.
            for (id in index..index + 1) {
                if (jobs[id] == null) {
                    jobs[id] = launch {
                        gate.awaitStable { id == current || id == current + 1 }
                        requested += id
                    }
                }
            }
            runCurrent()
            advanceTimeBy(150)
        }

        assertTrue(requested.isEmpty())
        advanceTimeBy(649)
        runCurrent()
        assertTrue(requested.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(24, 25), requested.sorted())
    }

    @Test
    fun singlePlaybackSelectionKeepsFastDelay() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })
        var requestedAt: Long? = null

        gate.onSelectionChanged()
        val job = launch {
            gate.awaitStable(
                requiredDelayMs = { PLAYBACK_RESOLVE_STABILITY_DELAY_MS },
            ) { true }
            requestedAt = currentTime
        }
        runCurrent()

        advanceTimeBy(PLAYBACK_RESOLVE_STABILITY_DELAY_MS - 1)
        runCurrent()
        assertEquals(null, requestedAt)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(PLAYBACK_RESOLVE_STABILITY_DELAY_MS, requestedAt)
        assertTrue(job.isCompleted)
    }

    @Test
    fun rapidPlaybackSkipsUseLongerSettleWindow() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })
        var requestedAt: Long? = null

        gate.onSelectionChanged()
        val job = launch {
            gate.awaitStable(
                requiredDelayMs = { PLAYBACK_RESOLVE_STABILITY_DELAY_MS },
            ) { true }
            requestedAt = currentTime
        }
        runCurrent()

        advanceTimeBy(150)
        gate.onSelectionChanged()
        runCurrent()
        advanceTimeBy(150)
        gate.onSelectionChanged()
        runCurrent()

        advanceTimeBy(RAPID_SKIP_PLAYBACK_SETTLE_DELAY_MS - 1)
        runCurrent()
        assertEquals(null, requestedAt)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(300L + RAPID_SKIP_PLAYBACK_SETTLE_DELAY_MS, requestedAt)
        assertTrue(job.isCompleted)
    }

    @Test
    fun calmSelectionAfterBurstReturnsToFastPlaybackDelay() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })

        gate.onSelectionChanged()
        advanceTimeBy(150)
        gate.onSelectionChanged()
        advanceTimeBy(150)
        gate.onSelectionChanged()

        advanceTimeBy(RAPID_SKIP_MAX_GAP_MS + 1)
        gate.onSelectionChanged()
        val selectedAt = currentTime
        var requestedAt: Long? = null
        val job = launch {
            gate.awaitStable(
                requiredDelayMs = { PLAYBACK_RESOLVE_STABILITY_DELAY_MS },
            ) { true }
            requestedAt = currentTime
        }
        runCurrent()

        advanceTimeBy(PLAYBACK_RESOLVE_STABILITY_DELAY_MS)
        runCurrent()
        assertEquals(selectedAt + PLAYBACK_RESOLVE_STABILITY_DELAY_MS, requestedAt)
        assertTrue(job.isCompleted)
    }

    @Test
    fun promotedPrefetchAdoptsShortPlaybackDelayImmediately() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })
        var current = 0
        var requestedAt: Long? = null

        val job = launch {
            gate.awaitStable(
                requiredDelayMs = {
                    if (current == 1) PLAYBACK_RESOLVE_STABILITY_DELAY_MS
                    else PREFETCH_RESOLVE_STABILITY_DELAY_MS
                },
            ) { current == 1 }
            requestedAt = currentTime
        }
        runCurrent()

        // Track 1 starts as PREFETCH, so it would normally wait 800 ms.
        advanceTimeBy(300)
        current = 1
        gate.onSelectionChanged()
        runCurrent()

        advanceTimeBy(PLAYBACK_RESOLVE_STABILITY_DELAY_MS - 1)
        runCurrent()
        assertEquals(null, requestedAt)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(550L, requestedAt)
        assertTrue(job.isCompleted)
    }

    @Test
    fun cancelledLoaderNeverStartsNetworkWork() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })
        var requested = false
        val job = launch {
            gate.awaitStable { true }
            requested = true
        }
        runCurrent()
        advanceTimeBy(200)
        job.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(requested)
        assertTrue(job.isCancelled)
    }

    @Test
    fun obsoleteTrackIsRejectedEvenIfLoaderArrivesLate() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })
        advanceTimeBy(1_000)
        var requested = false
        val job = launch {
            gate.awaitStable { false }
            requested = true
        }
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(requested)
    }

    @Test
    fun steadyPlaybackDoesNotDelayUrlRefresh() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })
        advanceTimeBy(10_000)
        val startedAt = currentTime
        gate.awaitStable { true }
        assertEquals(startedAt, currentTime)
    }
}
