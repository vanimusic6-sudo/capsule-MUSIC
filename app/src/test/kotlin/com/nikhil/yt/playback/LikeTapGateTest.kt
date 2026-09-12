package com.nikhil.yt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LikeTapGateTest {
    @Test fun rejectsDoubleTapWithoutDelayingTheNextIntentionalTap() {
        var now = 0L
        val gate = LikeTapGate(nowMs = { now })
        assertTrue(gate.accept("a"))
        now = 100
        assertFalse(gate.accept("a"))
        now = 499
        assertFalse(gate.accept("a"))
        now = 500
        assertTrue(gate.accept("a"))
    }

    @Test fun aDifferentSongCanBeLikedImmediately() {
        val gate = LikeTapGate(nowMs = { 0 })
        assertTrue(gate.accept("a"))
        assertTrue(gate.accept("b"))
    }
}
