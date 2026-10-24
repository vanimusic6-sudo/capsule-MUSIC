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
                AudioClientOrder.VISIONOS_0_1,
                AudioClientOrder.VISIONOS,
                AudioClientOrder.WEB_REMIX,
                AudioClientOrder.WEB_EMBEDDED,
            ),
            AudioWebStartupClientPolicy.effectiveOrder(configured, webReady = false),
        )
        assertEquals(
            configured,
            AudioWebStartupClientPolicy.effectiveOrder(configured, webReady = true),
        )
    }

    @Test fun tokenFreeEmbeddedPriorityIsNeverReordered() {
        val configured = listOf(AudioClientOrder.WEB_EMBEDDED, AudioClientOrder.VISIONOS)
        assertEquals(configured, AudioWebStartupClientPolicy.effectiveOrder(configured, false))
    }

    @Test fun tokenRequirementsComeFromThePinnedClientCatalogue() {
        org.junit.Assert.assertTrue(AudioWebStartupClientPolicy.requiresToken(AudioClientOrder.WEB_REMIX))
        org.junit.Assert.assertTrue(AudioWebStartupClientPolicy.requiresToken(AudioClientOrder.WEB_CREATOR))
        org.junit.Assert.assertTrue(AudioWebStartupClientPolicy.requiresToken(AudioClientOrder.TVHTML5_SIMPLY))
        org.junit.Assert.assertFalse(AudioWebStartupClientPolicy.requiresToken(AudioClientOrder.VISIONOS))
        org.junit.Assert.assertFalse(AudioWebStartupClientPolicy.requiresToken(AudioClientOrder.VISIONOS_0_1))
        org.junit.Assert.assertFalse(AudioWebStartupClientPolicy.requiresToken(AudioClientOrder.WEB_EMBEDDED))
    }

    @Test fun emptyOrderDoesNotInventClients() {
        assertEquals(emptyList<String>(), AudioWebStartupClientPolicy.effectiveOrder(emptyList(), false))
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
