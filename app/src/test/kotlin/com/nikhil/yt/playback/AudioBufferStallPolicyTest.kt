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

    @Test fun stalledAudioWithoutNewBufferCanRefresh() {
        assertTrue(needsRefresh(ahead = 0L, growth = 0L))
        assertTrue(needsRefresh(ahead = 900L, growth = 100L))
    }

    @Test fun progressOrAdequateBufferPreventsRefresh() {
        assertFalse(needsRefresh(ahead = 8_000L))
        assertFalse(needsRefresh(ahead = 0L, growth = 2_000L))
        assertFalse(needsRefresh(ahead = -1L))
    }

    @Test fun userPauseVideoOfflineAndOtherGuardsPreventRefresh() {
        assertFalse(needsRefresh(playWhenReady = false))
        assertFalse(needsRefresh(buffering = false))
        assertFalse(needsRefresh(connected = false))
        assertFalse(needsRefresh(blocked = true))
        assertFalse(needsRefresh(isVideo = true))
    }
}
