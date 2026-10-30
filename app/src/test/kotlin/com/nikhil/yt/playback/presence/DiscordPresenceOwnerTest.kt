package com.nikhil.yt.playback.presence

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordPresenceOwnerTest {
    @Test fun legacyUserTokenCannotEnableGatewayRPC() {
        listOf("token-a", "token-b", " ", "").forEach { token ->
            listOf(true, false).forEach { enabled ->
                assertEquals(
                    DiscordPresenceReconcileAction.STOP,
                    decideDiscordPresenceReconcileAction(
                        enabled = enabled,
                        configuredToken = token,
                        managerRunning = true,
                        activeToken = token,
                    ),
                )
            }
        }
    }
}
