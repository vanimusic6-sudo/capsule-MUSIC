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
