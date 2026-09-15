package com.nikhil.yt.playback.presence

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordPresenceOwnerTest {
    @Test
    fun sameRunningTokenKeepsExistingManager() {
        assertEquals(
            DiscordPresenceReconcileAction.KEEP,
            decideDiscordPresenceReconcileAction(
                enabled = true,
                configuredToken = "token-a",
                managerRunning = true,
                activeToken = "token-a",
            ),
        )
    }

    @Test
    fun changedTokenRestartsRunningManager() {
        assertEquals(
            DiscordPresenceReconcileAction.RESTART,
            decideDiscordPresenceReconcileAction(
                enabled = true,
                configuredToken = "token-b",
                managerRunning = true,
                activeToken = "token-a",
            ),
        )
    }

    @Test
    fun missingOrDisabledConfigurationStopsManager() {
        assertEquals(
            DiscordPresenceReconcileAction.STOP,
            decideDiscordPresenceReconcileAction(
                enabled = true,
                configuredToken = "   ",
                managerRunning = true,
                activeToken = "token-a",
            ),
        )
        assertEquals(
            DiscordPresenceReconcileAction.STOP,
            decideDiscordPresenceReconcileAction(
                enabled = false,
                configuredToken = "token-a",
                managerRunning = true,
                activeToken = "token-a",
            ),
        )
    }

    @Test
    fun enabledTokenStartsManagerWhenNothingIsRunning() {
        assertEquals(
            DiscordPresenceReconcileAction.RESTART,
            decideDiscordPresenceReconcileAction(
                enabled = true,
                configuredToken = "token-a",
                managerRunning = false,
                activeToken = null,
            ),
        )
    }
}
