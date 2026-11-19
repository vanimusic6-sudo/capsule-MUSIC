package com.nikhil.yt.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSceneTouchReleaseTest {
    @Test fun collapseReturnsDragOwnershipBeforeLastSpringPixel() {
        assertTrue(playerSceneShouldInterceptTouch(1f, expanding = false, dismissed = false))
        assertTrue(playerSceneShouldInterceptTouch(0.12f, expanding = false, dismissed = false))
        assertFalse(playerSceneShouldInterceptTouch(0.07f, expanding = false, dismissed = false))
        assertFalse(playerSceneShouldInterceptTouch(0f, expanding = false, dismissed = false))
    }

    @Test fun openingOwnsTouchesFromTheFirstFrame() {
        assertTrue(playerSceneShouldInterceptTouch(0f, expanding = true, dismissed = false))
        assertFalse(playerSceneShouldInterceptTouch(1f, expanding = true, dismissed = true))
        assertFalse(playerSceneShouldInterceptTouch(Float.NaN, expanding = false, dismissed = false))
    }
}
