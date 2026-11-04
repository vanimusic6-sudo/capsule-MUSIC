package com.nikhil.yt.playback.presence

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordPresenceOwnerTest {
    @Test fun unavailableGatewayNeverLaunchesAReconciliationForEachPlayerEvent() {
        assertEquals(false, LEGACY_DISCORD_GATEWAY_AVAILABLE)
        val content = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/playback/presence/DiscordPresenceOwner.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/playback/presence/DiscordPresenceOwner.kt"),
        ).first(java.io.File::isFile).readText()
        val hotPath = content.substringAfter("fun ensure() {").substringBefore("fun restart()")
        assertEquals(true, hotPath.contains("if (!LEGACY_DISCORD_GATEWAY_AVAILABLE)"))
        assertEquals(true, hotPath.contains("return"))
        val update = content.substringAfter("suspend fun updateNow(").substringBefore("suspend fun reconcile()")
        assertEquals(true, update.contains("if (!LEGACY_DISCORD_GATEWAY_AVAILABLE) return DiscordPresenceUpdateResult.SKIPPED"))
    }
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
