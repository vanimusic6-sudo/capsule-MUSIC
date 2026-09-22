package com.nikhil.yt.playback

import com.nikhil.yt.constants.AudioClientOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioWebStartupClientPolicyTest {
    @Test
    fun webFirstPreferenceUsesVisionUntilWebSessionIsPrepared() {
        val configured = listOf(
            AudioClientOrder.WEB_REMIX,
            AudioClientOrder.VISIONOS_0_1,
            AudioClientOrder.VISIONOS,
            AudioClientOrder.WEB_EMBEDDED,
        )
        assertEquals(
            listOf(
                AudioClientOrder.VISIONOS,
                AudioClientOrder.WEB_REMIX,
                AudioClientOrder.VISIONOS_0_1,
                AudioClientOrder.WEB_EMBEDDED,
            ),
            AudioWebStartupClientPolicy.effectiveOrder(configured, webReady = false),
        )
        assertEquals(
            configured,
            AudioWebStartupClientPolicy.effectiveOrder(configured, webReady = true),
        )
    }

    @Test
    fun existingVisionPriorityAndFallbackRemainUnchanged() {
        val configured = listOf(
            AudioClientOrder.VISIONOS,
            AudioClientOrder.WEB_REMIX,
            AudioClientOrder.WEB_EMBEDDED,
        )
        assertEquals(
            configured,
            AudioWebStartupClientPolicy.effectiveOrder(configured, webReady = false),
        )
    }

    @Test
    fun startupOnlyChangesTheEffectiveOrderNotPersistedUserOrder() {
        val configured = mutableListOf(
            AudioClientOrder.WEB_REMIX,
            AudioClientOrder.WEB_CREATOR,
            AudioClientOrder.VISIONOS,
        )
        AudioWebStartupClientPolicy.effectiveOrder(configured, webReady = false)
        assertEquals(
            listOf(AudioClientOrder.WEB_REMIX, AudioClientOrder.WEB_CREATOR, AudioClientOrder.VISIONOS),
            configured,
        )
    }
}
