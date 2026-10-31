package com.nikhil.yt.playback.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlaybackNetworkPriorityTest {
    private var nowMs = 10_000L
    private val priority = DownloadPlaybackNetworkPriority { nowMs }

    @Test fun downloadsYieldDuringForegroundBufferingButNeverIndefinitely() {
        priority.observe("song-a", playWhenReady = true, buffering = true, ready = false)
        assertTrue(priority.shouldYield())
        nowMs += DownloadPlaybackNetworkPriority.MAX_BUFFERING_YIELD_MS - 1
        assertTrue(priority.shouldYield())
        nowMs++
        assertFalse(priority.shouldYield())
    }

    @Test fun readyPlaybackGetsOneShortRefillWindowWithoutPollingExtension() {
        priority.observe("song-a", playWhenReady = true, buffering = true, ready = false)
        nowMs += 400
        priority.observe("song-a", playWhenReady = true, buffering = false, ready = true)
        assertTrue(priority.shouldYield())
        nowMs += DownloadPlaybackNetworkPriority.READY_REFILL_YIELD_MS - 1
        // Repeated Media3 events must not re-arm a timer.
        priority.observe("song-a", playWhenReady = true, buffering = false, ready = true)
        assertTrue(priority.shouldYield())
        nowMs++
        assertFalse(priority.shouldYield())
    }

    @Test fun pauseIdleAndClearImmediatelyReleaseDownloads() {
        priority.observe("song-a", playWhenReady = true, buffering = true, ready = false)
        assertTrue(priority.shouldYield())
        priority.observe("song-a", playWhenReady = false, buffering = true, ready = false)
        assertFalse(priority.shouldYield())
        priority.observe("song-a", playWhenReady = true, buffering = true, ready = false)
        priority.clear()
        assertFalse(priority.shouldYield())
    }

    @Test fun nextTrackGetsOwnHeadStartButAnIdleStateDoesNotGate() {
        priority.observe("song-a", playWhenReady = true, buffering = false, ready = false)
        assertFalse(priority.shouldYield())
        priority.observe("song-a", playWhenReady = true, buffering = true, ready = false)
        nowMs += DownloadPlaybackNetworkPriority.MAX_BUFFERING_YIELD_MS
        assertFalse(priority.shouldYield())
        priority.observe("song-b", playWhenReady = true, buffering = true, ready = false)
        assertTrue(priority.shouldYield())
    }
}
