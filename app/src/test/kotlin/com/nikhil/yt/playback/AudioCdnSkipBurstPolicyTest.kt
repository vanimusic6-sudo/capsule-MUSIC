package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCdnSkipBurstPolicyTest {
    private var now = 10_000L
    private val policy = AudioCdnSkipBurstPolicy { now }

    private fun quickSkip(from: String, to: String) {
        policy.onReady(from, playWhenReady = true)
        now += 500L
        policy.onTransition(to)
    }

    @Test
    fun firstTwoReadySkipsDoNotSlowNormalListening() {
        policy.onTransition("a")
        quickSkip("a", "b")
        quickSkip("b", "c")
        assertFalse(policy.isBurst("c"))
        assertEquals(0L, policy.firstOpenDelayMs("c", 0L))
        assertEquals(0L, policy.stagedFirstChunkBytes("c", 0L))
    }

    @Test
    fun confirmedBurstPacesOnlyCurrentFirstOpenAndStagesFirstChunk() {
        policy.onTransition("a")
        quickSkip("a", "b")
        quickSkip("b", "c")
        quickSkip("c", "d")
        assertTrue(policy.isBurst("d"))
        assertEquals(300L, policy.firstOpenDelayMs("d", 0L))
        assertEquals(256L * 1024, policy.stagedFirstChunkBytes("d", 0L))
        assertEquals(0L, policy.firstOpenDelayMs("d", 256L * 1024))
        assertEquals(0L, policy.stagedFirstChunkBytes("d", 256L * 1024))
        assertEquals(0L, policy.firstOpenDelayMs("old", 0L))
        assertEquals(0L, policy.stagedFirstChunkBytes("old", 0L))
    }

    @Test
    fun burstExpiresAndHealthyListeningRestoresImmediateOpens() {
        policy.onTransition("a")
        quickSkip("a", "b")
        quickSkip("b", "c")
        quickSkip("c", "d")
        now += 7_100L
        policy.onReady("d", playWhenReady = true)
        // A track that plays longer than the quick-skip window ends the burst.
        now += 7_100L
        policy.onTransition("e")
        assertFalse(policy.isBurst("e"))
        assertEquals(0L, policy.firstOpenDelayMs("e", 0L))
    }

    @Test
    fun pausedOrUnreadyTrackCannotIncreaseBurstCount() {
        policy.onTransition("a")
        policy.onReady("a", playWhenReady = false)
        now += 200
        policy.onTransition("b")
        quickSkip("b", "c")
        assertFalse(policy.isBurst("c"))
    }

    @Test
    fun burstDoesNotPersistAfterInactivity() {
        policy.onTransition("a")
        quickSkip("a", "b")
        quickSkip("b", "c")
        quickSkip("c", "d")
        now += 21_000L
        assertFalse(policy.isBurst("d"))
    }
}
