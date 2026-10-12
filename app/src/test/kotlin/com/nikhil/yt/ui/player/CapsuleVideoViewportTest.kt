package com.nikhil.yt.ui.player

import org.junit.Assert.*
import org.junit.Test

class CapsuleVideoViewportTest {
    @Test fun wideFrameFitsWithoutCropping() {
        val fit = capsuleVideoFitScale(2.35f, 16f / 9f)
        assertEquals(1f, fit.first, 0f)
        assertEquals((16f / 9f) / 2.35f, fit.second, 0.0001f)
    }
    @Test fun portraitFrameRetainsItsWholeHeightAndSquareHasSymmetricSideSpace() {
        val portrait = capsuleVideoFitScale(9f / 16f, 16f / 9f)
        assertEquals(1f, portrait.second, 0f)
        assertEquals((9f / 16f) / (16f / 9f), portrait.first, 0.0001f)
        assertEquals(1f, capsuleVideoFitScale(1f, 16f / 9f).second, 0f)
    }
}
