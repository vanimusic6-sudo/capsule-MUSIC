package com.nikhil.yt.extensions

import org.junit.Assert.*
import org.junit.Test

class PlaybackBufferingTrackerTest {
    private var now = 0L
    private val tracker = PlaybackBufferingTracker { now }

    @Test
    fun skippingWhileBufferingClosesTheOldSongAndStartsANewMeasurement() {
        val first = tracker.update("a", 0, buffering = true, ready = false).single()
        now = 40_000
        val transition = tracker.update("b", 1, buffering = true, ready = false, boundary = "transition")
        assertEquals(listOf("cancel", "start"), transition.map { it.phase })
        assertEquals("a", transition.first().mediaId)
        assertEquals(40_000L, transition.first().durationMs)
        assertTrue(transition.last().generation > first.generation)
        assertEquals("transition", transition.last().kind)
        now = 41_000
        val end = tracker.update("b", 1, buffering = false, ready = true).single()
        assertEquals("b", end.mediaId)
        assertEquals(1_000L, end.durationMs)
    }

    @Test
    fun unrelatedEventBatchesDoNotRestartTheTimer() {
        tracker.update("a", 0, buffering = true, ready = false)
        now = 400
        assertTrue(tracker.update("a", 0, buffering = true, ready = false).isEmpty())
        now = 900
        assertEquals(900L, tracker.update("a", 0, buffering = false, ready = true).single().durationMs)
    }

    @Test
    fun aSeekWithinTheSameSongCannotInheritPreviousBuffering() {
        tracker.update("a", 0, buffering = true, ready = false)
        now = 800
        val seek = tracker.update("a", 0, buffering = true, ready = false, boundary = "seek")
        assertEquals(listOf("cancel", "start"), seek.map { it.phase })
        assertEquals("seek", seek.last().kind)
        now = 1000
        assertEquals(200L, tracker.update("a", 0, buffering = false, ready = true).single().durationMs)
    }

    @Test
    fun duplicateQueueEntriesAndRepeatsHaveSeparateGenerations() {
        val first = tracker.update("a", 0, buffering = true, ready = false).single()
        val duplicate = tracker.update("a", 1, buffering = true, ready = false).last()
        val repeat = tracker.update("a", 1, buffering = true, ready = false, boundary = "transition").last()
        assertTrue(duplicate.generation > first.generation)
        assertTrue(repeat.generation > duplicate.generation)
    }

    @Test
    fun startupAndRebufferingAreDistinguished() {
        assertEquals("initial", tracker.update("a", 0, buffering = true, ready = false).single().kind)
        tracker.update("a", 0, buffering = false, ready = true)
        assertEquals("rebuffer", tracker.update("a", 0, buffering = true, ready = false).single().kind)
    }

    @Test
    fun stoppingOrClearingTheQueueCancelsInsteadOfReportingSuccess() {
        tracker.update("a", 0, buffering = true, ready = false)
        assertEquals("cancel", tracker.update("a", 0, buffering = false, ready = false).single().phase)
        tracker.update("a", 0, buffering = true, ready = false)
        assertEquals("cancel", tracker.update(null, -1, buffering = false, ready = false).single().phase)
        assertTrue(tracker.update(null, -1, buffering = false, ready = false).isEmpty())
    }

    @Test
    fun disablingDiagnosticsCannotLeaveAnOldIntervalToFinishLater() {
        tracker.update("a", 0, buffering = true, ready = false)
        tracker.reset()
        now = 60_000
        assertTrue(tracker.update("a", 0, buffering = false, ready = true).isEmpty())
    }
}
