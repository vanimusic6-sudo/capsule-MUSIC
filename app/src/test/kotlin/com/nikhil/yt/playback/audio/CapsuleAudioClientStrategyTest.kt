package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.strategy.PoTokenRequirement
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
     * Every client keeps the identity the catalogue gives it.
     *
     * Stripping WEB_REMIX's identity to match the anonymous session its poToken is minted for was
     * tried against the 403s. On a device it did not fix them, and it broke the account: an
     * anonymous player request cannot carry the account that is allowed to play age-restricted
     * tracks, so those started demanding a sign-in that was already there. This pins the identity so
     * that failure cannot come back by way of a plausible-sounding theory.
     */
    @Test
    fun noClientLosesTheIdentityTheCatalogueGivesIt() {
        AudioStreamPolicy.entries.filter { it.isUserSelectable }.forEach { policy ->
            val selection = request(policy.normalizedForPlayback())
            val fromLibrary =
                ContentAwareFallbackStrategy()
                    .selectClients(selection)
                    .candidates
                    .single { it.manifest?.id == policy.playbackClientOverrideId }
            val ours = CapsuleAudioClientStrategy.selectClients(selection).candidates.single()

            assertEquals(
                "$policy must authenticate exactly as the catalogue describes",
                requireNotNull(fromLibrary.manifest).authentication,
                requireNotNull(ours.manifest).authentication,
            )
            assertEquals(
                "$policy must keep its cookies",
                fromLibrary.manifest!!.request.cookies,
                ours.manifest!!.request.cookies,
            )
            assertEquals(
                "$policy must keep its login support",
                fromLibrary.client.loginSupported,
                ours.client.loginSupported,
            )
            assertEquals(
                "$policy must keep its login requirement",
                fromLibrary.client.loginRequired,
                ours.client.loginRequired,
            )
        }
    }

    /**
     * The one client that carries the account keeps carrying it.
     *
     * WEB_REMIX is how signed-in-only content is reached; without its cookies, age-restricted
     * tracks are refused however the account is linked.
     */
    @Test
    fun theAccountCarryingClientStillCarriesTheAccount() {
        val candidate =
            CapsuleAudioClientStrategy
                .selectClients(request(AudioStreamPolicy.WEB.normalizedForPlayback()))
                .candidates
                .single()

        assertTrue("WEB_REMIX must still be able to sign in", candidate.client.loginSupported)
        assertTrue("and still send its cookies", requireNotNull(candidate.manifest).request.cookies)
    }

    @Test
    fun theWebRemixPlayerRequestNowCarriesItsPoToken() {
        val candidate =
            CapsuleAudioClientStrategy
                .selectClients(request(AudioStreamPolicy.WEB.normalizedForPlayback()))
                .candidates
                .single()
        val poTokens = requireNotNull(candidate.manifest).poTokens

        assertEquals(
            "WEB_REMIX asks for a token on its streams",
            PoTokenRequirement.REQUIRED,
            poTokens.gvs.requirement,
        )
        assertEquals(
            "so it has to send one with the player request too",
            PoTokenRequirement.REQUIRED,
            poTokens.player.requirement,
        )
        // Raised to exactly what the manifest already declares for its streams: the binding and the
        // providers are the client's own, not something invented here.
        assertEquals(poTokens.gvs.binding, poTokens.player.binding)
        assertEquals(poTokens.gvs.providers, poTokens.player.providers)
        assertTrue(
            "and a real provider must remain, or the token can never be obtained",
            poTokens.player.providers.isNotEmpty(),
        )
    }

    /**
     * A client that never wanted a token is not given one.
     *
     * Adding one where the catalogue says none is needed would be inventing a requirement, and a
     * client that cannot satisfy an invented requirement is a client that stops being usable.
     */
    @Test
    fun clientsThatNeedNoPoTokenAreNotGivenOne() {
        listOf(AudioStreamPolicy.VISIONOS, AudioStreamPolicy.WEB_EMBEDDED).forEach { policy ->
            val candidate =
                CapsuleAudioClientStrategy
                    .selectClients(request(policy.normalizedForPlayback()))
                    .candidates
                    .single()
            val poTokens = requireNotNull(candidate.manifest).poTokens

            assertEquals("$policy uses no token on its streams", PoTokenRequirement.NONE, poTokens.gvs.requirement)
            assertEquals(
                "$policy must not be given one on its player request",
                PoTokenRequirement.NONE,
                poTokens.player.requirement,
            )
        }
    }

    /** Nothing here may change which client a policy resolves to. */
    @Test
    fun bindingThePoTokenDoesNotChangeWhoIsChosen() {
        AudioStreamPolicy.entries.filter { it.isUserSelectable }.forEach { policy ->
            val result = CapsuleAudioClientStrategy.selectClients(request(policy.normalizedForPlayback()))
            assertEquals(
                "$policy resolved somewhere else",
                listOf(policy.playbackClientOverrideId),
                result.candidates.map { it.manifest?.id },
            )
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
