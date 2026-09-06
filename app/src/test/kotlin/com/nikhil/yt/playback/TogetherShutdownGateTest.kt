package com.nikhil.yt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherShutdownGateTest {
    @Test
    fun shutdownCanOnlyBeginOnce() {
        val gate = TogetherShutdownGate()

        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        assertFalse(gate.tryBegin())
    }
}
