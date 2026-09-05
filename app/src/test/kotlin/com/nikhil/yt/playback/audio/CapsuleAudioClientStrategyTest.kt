package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.nikhil.yt.constants.AudioStreamPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleAudioClientStrategyTest {
    @Test
    fun selectablePoliciesReachTheirActualLibraryClient() {
        val expected = mapOf(
            AudioStreamPolicy.VISIONOS to "VISIONOS",
            AudioStreamPolicy.WEB to "WEB_REMIX",
            AudioStreamPolicy.WEB_EMBEDDED to "WEB_EMBEDDED_PLAYER",
        )
        assertEquals(expected.keys, AudioStreamPolicy.entries.filter { it.isUserSelectable }.toSet())
        expected.forEach { (policy, clientName) ->
            val savedPolicy = AudioStreamPolicy.valueOf(policy.name).normalizedForPlayback()
            val result = CapsuleAudioClientStrategy.selectClients(request(savedPolicy))
            assertEquals(listOf(clientName), result.candidates.map { it.client.clientName })
        }
    }

    @Test
    fun excludedManualClientDoesNotSilentlyFallBackToAnotherClient() {
        AudioStreamPolicy.entries.filter { it.isUserSelectable }.forEach { policy ->
            val result = CapsuleAudioClientStrategy.selectClients(
                request(policy).copy(excludedClients = setOf(policy.playbackClientOverrideId)),
            )
            assertTrue("Unexpected fallback for $policy", result.candidates.isEmpty())
        }
    }

    @Test
    fun legacyPoliciesStillResolveToVisionOS() {
        AudioStreamPolicy.entries.filterNot { it.isUserSelectable }.forEach { policy ->
            val result = CapsuleAudioClientStrategy.selectClients(request(policy.normalizedForPlayback()))
            assertEquals(listOf("VISIONOS"), result.candidates.map { it.client.clientName })
        }
    }

    private fun request(policy: AudioStreamPolicy) = ClientSelectionRequest(
        hints = ContentHints(playbackClientOverrideId = policy.playbackClientOverrideId),
        authenticated = false,
        availablePoTokenProviders = setOf(PoTokenProviderKind.WEB_BOTGUARD),
        javaScriptRuntimeAvailable = true,
        webViewAvailable = true,
    )
}
