package com.nikhil.yt.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherSessionControllerTest {
    @Test
    fun displayNameIsTrimmedBeforeHostingOrJoining() {
        assertEquals("Capsule Guest", normalizedTogetherDisplayName("  Capsule Guest  ", "Guest"))
    }

    @Test
    fun blankDisplayNameFallsBackToRoleName() {
        assertEquals("Guest", normalizedTogetherDisplayName("   ", "Guest"))
    }

    @Test
    fun initialGuestStatePreservesWelcomeIdentityAndSettings() {
        val settings =
            TogetherRoomSettings(
                allowGuestsToAddTracks = false,
                allowGuestsToControlPlayback = true,
                requireHostApprovalToJoin = true,
            )

        val state =
            initialGuestRoomState(
                sessionId = "session",
                hostId = "host",
                participantId = "guest-1",
                displayName = "Guest One",
                isPending = true,
                settings = settings,
                sentAtElapsedRealtimeMs = 1_234L,
            )

        assertEquals("session", state.sessionId)
        assertEquals("host", state.hostId)
        assertEquals(settings, state.settings)
        assertEquals(1_234L, state.sentAtElapsedRealtimeMs)
        assertTrue(state.queue.isEmpty())
        assertFalse(state.isPlaying)
        assertEquals(1, state.participants.size)
        assertEquals("guest-1", state.participants.single().id)
        assertEquals("Guest One", state.participants.single().name)
        assertTrue(state.participants.single().isPending)
    }

    @Test
    fun transportCadencesRemainCompatibleWithExistingProtocol() {
        assertEquals(750L, TogetherSessionController.HOST_BROADCAST_INTERVAL_MS)
        assertEquals(2_000L, TogetherSessionController.GUEST_HEARTBEAT_INTERVAL_MS)
    }
}
