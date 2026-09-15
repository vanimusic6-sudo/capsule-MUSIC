package com.nikhil.yt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LikeTapGateTest {
    @Test fun acceptsRapidRepeatedTapsOnTheSameSong() {
        val gate = LikeTapGate()

        repeat(4) {
            assertTrue(gate.accept("a"))
        }
    }

    @Test fun aDifferentSongCanBeLikedImmediately() {
        val gate = LikeTapGate()
        assertTrue(gate.accept("a"))
        assertTrue(gate.accept("b"))
    }

    @Test fun rejectsMissingMediaIds() {
        val gate = LikeTapGate()
        assertFalse(gate.accept(""))
        assertFalse(gate.accept("   "))
    }
}
