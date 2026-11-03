package com.nikhil.yt.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEventGateTest {
    @Test fun inactivePlaybackSleepsForAMinuteWithoutOnePeriodicWakeup() = runTest {
        val gate = PlaybackEventGate()
        var wakes = 0
        val job = backgroundScope.launch { gate.awaitChange(); wakes++ }
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, wakes)
        gate.signal(); runCurrent()
        assertEquals(1, wakes)
        assertTrue(job.isCompleted)
    }

    @Test fun anEventBeforeSuspensionIsRetainedAndBurstsAreCoalesced() = runTest {
        val gate = PlaybackEventGate()
        repeat(100) { gate.signal() }
        var wakes = 0
        backgroundScope.launch { gate.awaitChange(); wakes++; gate.awaitChange(); wakes++ }
        runCurrent()
        assertEquals(1, wakes)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, wakes)
        gate.signal(); runCurrent()
        assertEquals(2, wakes)
    }

    @Test fun seeksPreemptTheDeadlineAndAQuietTrackStillReachesItsPreloadWindow() = runTest {
        val gate = PlaybackEventGate()
        var wakes = 0
        backgroundScope.launch { gate.awaitChange(30_000); wakes++; gate.awaitChange(1_000); wakes++ }
        runCurrent()
        advanceTimeBy(150); gate.signal(); runCurrent()
        assertEquals(1, wakes)
        advanceTimeBy(999); runCurrent(); assertEquals(1, wakes)
        advanceTimeBy(1); runCurrent(); assertEquals(2, wakes)
    }
}
