package com.nikhil.yt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBufferStallPolicyTest {
    private fun needsRefresh(
        buffering: Boolean = true,
        playWhenReady: Boolean = true,
        connected: Boolean = true,
        blocked: Boolean = false,
        isVideo: Boolean = false,
        ahead: Long = 0L,
        growth: Long = 0L,
    ): Boolean = AudioBufferStallPolicy.shouldRefresh(
        buffering, playWhenReady, connected, blocked, isVideo, ahead, growth,
    )

    @Test fun completelyStalledStartupAndMidSongRebufferRecoverWithinBoundedWindows() {
        assertTrue(AudioBufferStallPolicy.INITIAL_WAIT_MS in 10_000L..14_000L)
        assertTrue(AudioBufferStallPolicy.INITIAL_WAIT_MS +
            AudioBufferStallPolicy.SAMPLE_WINDOW_MS <= 15_000L)
        assertTrue(AudioBufferStallPolicy.REBUFFER_WAIT_MS +
            AudioBufferStallPolicy.SAMPLE_WINDOW_MS <= 9_000L)
    }

    @Test fun shortTracksThatNeverStartedMaySwitchToAnotherClient() {
        val policy = AudioBufferStallPolicy
        assertTrue(policy.shouldRotateClientAfterStall(
            2L * 1024 * 1024, false, false, 0, hasEverBeenReady = false))
        assertFalse(policy.shouldRotateClientAfterStall(
            2L * 1024 * 1024, false, false, 0, hasEverBeenReady = true))
        assertTrue(policy.shouldRotateClientAfterStall(
            2L * 1024 * 1024, false, false, 1, hasEverBeenReady = true))
        assertTrue(policy.shouldRotateClientAfterStall(
            30L * 1024 * 1024, false, false, 0, hasEverBeenReady = true))
        assertFalse(policy.shouldRotateClientAfterStall(
            30L * 1024 * 1024, true, false, 0, hasEverBeenReady = false))
        assertFalse(policy.shouldRotateClientAfterStall(
            30L * 1024 * 1024, false, true, 0, hasEverBeenReady = false))
        assertFalse(policy.shouldRotateClientAfterStall(
            30L * 1024 * 1024, false, false, 2, hasEverBeenReady = false))
    }

    @Test fun stalledAudioWithoutNewBufferCanRefresh() {
        assertTrue(needsRefresh(ahead = 0L, growth = 0L))
        assertTrue(needsRefresh(ahead = 900L, growth = 100L))
        assertTrue(needsRefresh(ahead = 900L, growth = 1_100L))
    }

    @Test fun progressOrAdequateBufferPreventsRefresh() {
        assertFalse(needsRefresh(ahead = 8_000L))
        assertFalse(needsRefresh(ahead = 0L, growth = 1_500L))
        assertFalse(needsRefresh(ahead = -1L))
    }

    @Test fun onlyRepeatedConfirmedLongFormStallsPromoteDirectClients() {
        val size = 46L * 1024 * 1024
        val policy = AudioBufferStallPolicy
        assertTrue(policy.shouldRotateLongFormClient(size, false, false, 0))
        assertTrue(policy.shouldRotateLongFormClient(size, false, false, 1))
        assertFalse(policy.shouldRotateLongFormClient(size, false, false, 2))
        assertFalse(policy.shouldRotateLongFormClient(size, false, true, 0))
        assertFalse(policy.shouldRotateLongFormClient(size, true, false, 0))
        assertFalse(policy.shouldRotateLongFormClient(3L * 1024 * 1024, false, false, 0))
        assertFalse(policy.shouldRotateLongFormClient(null, false, false, 0))
    }

    @Test fun userPauseVideoOfflineAndOtherGuardsPreventRefresh() {
        assertFalse(needsRefresh(playWhenReady = false))
        assertFalse(needsRefresh(buffering = false))
        assertFalse(needsRefresh(connected = false))
        assertFalse(needsRefresh(blocked = true))
        assertFalse(needsRefresh(isVideo = true))
    }
}
