package com.nikhil.yt.together

import org.junit.Assert.assertEquals
import org.junit.Test

class TogetherSessionControllerTest {
    @Test
    fun displayNameIsTrimmedBeforeHosting() {
        assertEquals("Capsule Host", normalizedTogetherDisplayName("  Capsule Host  ", "Capsule"))
    }

    @Test
    fun blankDisplayNameFallsBackToApplicationName() {
        assertEquals("Capsule", normalizedTogetherDisplayName("   ", "Capsule"))
    }

    @Test
    fun broadcastIntervalKeepsExistingSessionCadence() {
        assertEquals(750L, TogetherSessionController.HOST_BROADCAST_INTERVAL_MS)
    }
}
