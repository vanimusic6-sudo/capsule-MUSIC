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
    @Test fun audioReadyCannotReleaseDownloadsWhileVideoIsPreparing() {
        priority.observe("song-a", true, false, true)
        nowMs += 3_000
        priority.observe("song-a", true, true, false, owner = "video")
        assertTrue(priority.shouldYield())
        // Audio is already ready; another event must preserve the VIDEO lease.
        priority.observe("song-a", true, false, true)
        assertTrue(priority.shouldYield())
        priority.clear("video")
        assertFalse(priority.shouldYield())
    }

    @Test fun videoPauseCannotClearAnAudioBufferingLeaseAndHoldRemainsBounded() {
        priority.observe("song-a", true, true, false)
        priority.observe("song-a", true, true, false, owner = "video")
        priority.observe("song-a", false, false, false, owner = "video")
        assertTrue(priority.shouldYield())
        nowMs += DownloadPlaybackNetworkPriority.MAX_BUFFERING_YIELD_MS
        assertFalse(priority.shouldYield())
    }

    @Test fun lowHeadroomAtReadyKeepsDownloadsYieldingUntilAudioHasTimeToRefill() {
        priority.observe("song-a", playWhenReady = true, buffering = true, ready = false)
        nowMs += 1_000L
        priority.observe("song-a", playWhenReady = true, buffering = false, ready = true,
            bufferedAheadMs = 2_832L)
        nowMs += 3_000L
        assertTrue("2.8s buffered must not release all downloads after 2.5s",
            priority.shouldYield())
        nowMs += 5_001L
        assertFalse("Low-buffer network priority must expire", priority.shouldYield())
    }

    @Test fun healthyReadyBufferStillUsesShortYieldAndRepeatedEventsDoNotRearm() {
        priority.observe("song-a", playWhenReady = true, buffering = false, ready = true,
            bufferedAheadMs = 40_000L)
        nowMs += 2_000L
        priority.observe("song-a", playWhenReady = true, buffering = false, ready = true,
            bufferedAheadMs = 1_000L)
        nowMs += 501L
        assertFalse("A repeated READY event cannot extend the original gate",
            priority.shouldYield())
    }

}
