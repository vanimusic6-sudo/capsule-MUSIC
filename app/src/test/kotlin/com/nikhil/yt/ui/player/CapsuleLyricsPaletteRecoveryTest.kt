package com.nikhil.yt.ui.player

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleLyricsPaletteRecoveryTest {
    private val previous = listOf(Color.Red, Color.Green, Color.Blue)
    private val next = listOf(Color.Cyan, Color.Magenta, Color.Yellow)
    private val pending = List(3) { Color(0xFF1A1B21) }

    @Test fun pendingArtworkCannotEraseLastPreparedGradient() {
        assertEquals(previous, capsuleStableLyricsPalette(null, pending, false, previous))
    }

    @Test fun newestDecodedTrackReplacesRetainedColors() {
        assertEquals(next, capsuleStableLyricsPalette(null, next, true, previous))
    }

    @Test fun snapshotAppliesOnlyWhilePlayerPageOpens() {
        assertEquals(previous, capsuleStableLyricsPalette(previous, next, true, next))
        assertEquals(next, capsuleStableLyricsPalette(null, next, true, previous))
    }

    @Test fun missingArtworkWithoutPreviousSampleStartsQuietlyNeutral() {
        assertEquals(pending, capsuleStableLyricsPalette(null, pending, false, emptyList()))
    }

    @Test fun cachedScenesRemainMarkedAsReadyAndSyntheticFallbacksDoNot() {
        val ready = CapsuleArtworkScene(next)
        val placeholder = CapsuleArtworkScene(pending, ready = false)
        assertTrue(ready.ready)
        assertFalse(placeholder.ready)
    }
}
