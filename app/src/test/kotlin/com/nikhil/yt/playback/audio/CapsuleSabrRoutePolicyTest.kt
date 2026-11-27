package com.nikhil.yt.playback.audio

import org.junit.Assert.*
import org.junit.Test

class CapsuleSabrRoutePolicyTest {
    @Test fun noAutomaticSabrEvenAfterDirectFailures() {
        assertNull(CapsuleSabrRoutePolicy.select(false, false, setOf("WEB_REMIX", "VISIONOS_0_1")))
        assertNull(CapsuleSabrRoutePolicy.select(false, false, emptySet()))
    }
    @Test fun explicitDiagnosticIsSingleAttemptAndNeverDownloads() {
        assertEquals(CapsuleSabrRoutePolicy.PROFILE, CapsuleSabrRoutePolicy.select(true, false, emptySet()))
        assertNull(CapsuleSabrRoutePolicy.select(true, false, setOf(CapsuleSabrRoutePolicy.PROFILE)))
        assertNull(CapsuleSabrRoutePolicy.select(true, true, emptySet()))
    }
}
