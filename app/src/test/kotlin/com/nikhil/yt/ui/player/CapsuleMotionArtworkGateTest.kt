package com.nikhil.yt.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleMotionArtworkGateTest {
    @Test fun visibleAndPlayingAudioCanShowMotion() {
        assertTrue(capsuleShouldPlayMotionArtwork(
            true, true, true, true, false, false,
        ))
    }

    @Test fun newLookupNeverStartsWhileVideoOrPlayerIsHidden() {
        assertFalse(capsuleShouldPlayMotionArtwork(true, true, true, true, true, false))
        assertFalse(capsuleShouldPlayMotionArtwork(true, true, true, true, false, true))
        assertFalse(capsuleShouldPlayMotionArtwork(true, true, false, true, false, false))
        assertFalse(capsuleShouldPlayMotionArtwork(true, false, true, true, false, false))
        assertFalse(capsuleShouldPlayMotionArtwork(true, true, true, false, false, false))
        assertFalse(capsuleShouldPlayMotionArtwork(false, true, true, true, false, false))
    }
}
