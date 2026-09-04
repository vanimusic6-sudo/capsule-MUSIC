/*
 * Velune - by Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsecutiveTrackFailureGuardTest {
    @Test
    fun opensOnThirdDifferentFailedTrack() {
        val guard = ConsecutiveTrackFailureGuard(maxConsecutiveFailures = 3)

        assertTrue(guard.recordFailure("track-a"))
        assertTrue(guard.recordFailure("track-b"))
        assertFalse(guard.recordFailure("track-c"))

        assertEquals(3, guard.failureCount)
        assertTrue(guard.isOpen)
        assertFalse(guard.recordFailure("track-d"))
    }

    @Test
    fun duplicateFailureDoesNotAdvanceQueueOrIncrementCounter() {
        val guard = ConsecutiveTrackFailureGuard(maxConsecutiveFailures = 3)

        assertTrue(guard.recordFailure("track-a"))
        assertFalse(guard.recordFailure("track-a"))

        assertEquals(1, guard.failureCount)
        assertFalse(guard.isOpen)
    }

    @Test
    fun readyPlaybackClosesCircuitAndStartsAHealthySequence() {
        val guard = ConsecutiveTrackFailureGuard(maxConsecutiveFailures = 3)

        guard.recordFailure("track-a")
        guard.recordFailure("track-b")
        guard.recordFailure("track-c")
        guard.onHealthyPlayback()

        assertEquals(0, guard.failureCount)
        assertFalse(guard.isOpen)
        assertTrue(guard.recordFailure("track-d"))
    }
}
