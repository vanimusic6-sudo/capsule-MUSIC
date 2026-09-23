package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
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
    fun missingOverrideKeepsMaintainedContentAwareFallback() {
        val request = requestWithoutOverride()
        val expected = ContentAwareFallbackStrategy().selectClients(request)
        val actual = CapsuleAudioClientStrategy.selectClients(request)

        assertEquals(
            expected.candidates.map { it.client.clientName },
            actual.candidates.map { it.client.clientName },
        )
    }

    @Test
    fun legacyPoliciesStillResolveToVisionOS() {
        AudioStreamPolicy.entries.filterNot { it.isUserSelectable }.forEach { policy ->
            val result = CapsuleAudioClientStrategy.selectClients(request(policy.normalizedForPlayback()))
            assertEquals(listOf("VISIONOS"), result.candidates.map { it.client.clientName })
        }
    }

    /**
     * A selected client is handed on exactly as the library describes it.
     *
     * Two attempts to fix WEB_REMIX's 403s edited the manifest on the way past — first raising its
     * player poToken rule to the VIDEO_ID-bound one meant for its media URLs, then stripping its
     * cookies and login to match the anonymous session that token seemed to belong to. Neither
     * fixed a 403, and together they took the account down: age-restricted tracks demanded a
     * sign-in that was already there. This test exists so that no third attempt can be made here
     * without deleting it first, deliberately.
     */
    @Test
    fun aSelectedClientIsPassedOnExactlyAsTheLibraryDescribesIt() {
        AudioStreamPolicy.entries.filter { it.isUserSelectable }.forEach { policy ->
            val selection = request(policy.normalizedForPlayback())
            val fromLibrary =
                ContentAwareFallbackStrategy()
                    .selectClients(selection)
                    .candidates
                    .single { it.manifest?.id == policy.playbackClientOverrideId }
            val ours = CapsuleAudioClientStrategy.selectClients(selection).candidates.single()

            assertEquals("$policy was redescribed on the way past", fromLibrary, ours)
        }
    }

    private fun request(policy: AudioStreamPolicy) = ClientSelectionRequest(
        hints = ContentHints(playbackClientOverrideId = policy.playbackClientOverrideId),
        authenticated = false,
        availablePoTokenProviders = setOf(PoTokenProviderKind.WEB_BOTGUARD),
        javaScriptRuntimeAvailable = true,
        webViewAvailable = true,
    )

    private fun requestWithoutOverride() = ClientSelectionRequest(
        hints = ContentHints(),
        authenticated = false,
        availablePoTokenProviders = setOf(PoTokenProviderKind.WEB_BOTGUARD),
        javaScriptRuntimeAvailable = true,
        webViewAvailable = true,
    )
}
