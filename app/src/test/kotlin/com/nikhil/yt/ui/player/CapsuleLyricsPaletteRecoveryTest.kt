package com.nikhil.yt.ui.player

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Texts and normal Player paint the same upstream RGB frame. A short aperture
 * freeze is allowed; an independent 1400ms lag is not.
 */
class CapsuleLyricsPaletteRecoveryTest {
    private val previous = listOf(Color.Red, Color.Green, Color.Blue)
    private val next = listOf(Color.Cyan, Color.Magenta, Color.Yellow)
    private val pending = List(3) { Color(0xFF1A1B21) }

    @Test fun pendingArtworkUsesTheExactPlayerPendingColors() {
        assertEquals(pending, capsuleLyricsSharedColors(null, pending))
    }

    @Test fun newestDecodedTrackAppearsInTheSameFrameAsThePlayer() {
        assertEquals(next, capsuleLyricsSharedColors(null, next))
    }

    @Test fun openingFreezeIsReleasedAfterMorph() {
        assertEquals(previous, capsuleLyricsSharedColors(previous, next))
        assertEquals(next, capsuleLyricsSharedColors(null, next))
    }

    @Test fun missingParentColorsHaveStableNeutralFallback() {
        assertEquals(pending, capsuleLyricsSharedColors(null, emptyList()))
    }

    @Test fun pendingScenesAreNotMistakenForDecodedArtwork() {
        val ready = CapsuleArtworkScene(next)
        val placeholder = CapsuleArtworkScene(pending, ready = false)
        assertTrue(ready.ready)
        assertFalse(placeholder.ready)
    }
}
