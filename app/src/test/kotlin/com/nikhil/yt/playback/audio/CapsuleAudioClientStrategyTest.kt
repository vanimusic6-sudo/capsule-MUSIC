package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.strategy.AuthenticationPolicy
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
     * The proof-of-origin token goes with the player request, not only on the media URL.
     *
     * WEB_REMIX is the odd one out in the shipped catalogue: it declares `player = NONE` with
     * `gvs = REQUIRED`, so the player request went out anonymous and the token was appended to the
     * media URL afterwards — which YouTube refuses. In a capture of ordinary listening every
     * WEB_REMIX stream came back 403, twenty of them, and each rolled over to another client.
     *
     * Every other client in the catalogue has a consistent pair, and every one of them works:
     * TVHTML5_SIMPLY is REQUIRED on both and plays, the visionOS clients and WEB_EMBEDDED ask for
     * no token anywhere and play. WEB_REMIX is the only mismatch and the only failure.
     */
    @Test
    fun aTokenUsingClientAsksAsWhoeverTheTokenWasMintedFor() {
        val candidate =
            CapsuleAudioClientStrategy
                .selectClients(request(AudioStreamPolicy.WEB.normalizedForPlayback()))
                .candidates
                .single()
        val manifest = requireNotNull(candidate.manifest)

        // The token is minted against the anonymous visitor session, so the request that uses it
        // must be anonymous too. TVHTML5_SIMPLY needs a token and is exactly this shape, and plays;
        // WEB_REMIX needed a token, signed its request as the account, and got 403 every time.
        assertEquals(AuthenticationPolicy.UNSUPPORTED, manifest.authentication)
        assertEquals("no cookies may go with it", false, manifest.request.cookies)
        assertEquals("and no login either", false, candidate.client.loginSupported)
        assertEquals(false, candidate.client.loginRequired)
    }

    /** A client that needs no token keeps whatever identity its manifest describes. */
    @Test
    fun aClientThatNeedsNoTokenKeepsItsOwnIdentity() {
        listOf(AudioStreamPolicy.VISIONOS, AudioStreamPolicy.WEB_EMBEDDED).forEach { policy ->
            val fromLibrary =
                ContentAwareFallbackStrategy()
                    .selectClients(request(policy.normalizedForPlayback()))
                    .candidates
                    .single { it.manifest?.id == policy.playbackClientOverrideId }
            val ours =
                CapsuleAudioClientStrategy
                    .selectClients(request(policy.normalizedForPlayback()))
                    .candidates
                    .single()

            assertEquals(
                "$policy must be left exactly as the catalogue describes it",
                requireNotNull(fromLibrary.manifest).authentication,
                requireNotNull(ours.manifest).authentication,
            )
            assertEquals(fromLibrary.manifest!!.request.cookies, ours.manifest!!.request.cookies)
            assertEquals(fromLibrary.client.loginSupported, ours.client.loginSupported)
        }
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
