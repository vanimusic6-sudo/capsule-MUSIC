package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.AuthenticationPolicy
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PoTokenRequirement
import com.metrolist.innertubex.extraction.strategy.SelectedClient
import com.metrolist.innertubex.models.YouTubeClient

/**
 * Keep explicit playback choices strict without breaking generic InnerTubeX calls.
 *
 * Capsule playback always supplies [ContentHints.playbackClientOverrideId]. In that
 * mode the selected profile is authoritative: if the library excludes it, return no
 * candidates rather than silently rotating identities. Internal library/prewarm calls
 * that do not carry an override keep the maintained content-aware fallback behavior.
 */
internal object CapsuleAudioClientStrategy : ClientFallbackStrategy {
    private val delegate = ContentAwareFallbackStrategy()

    override fun resolveClients(hints: ContentHints): List<YouTubeClient> =
        selectClients(ClientSelectionRequest(hints = hints, authenticated = false))
            .candidates.map { it.client }

    override fun selectClients(request: ClientSelectionRequest): ClientSelectionResult {
        val selection = delegate.selectClients(request)
        val bound = selection.copy(candidates = selection.candidates.map(::matchTokenSession))
        val overrideId = request.hints.playbackClientOverrideId ?: return bound
        return bound.copy(
            candidates = bound.candidates.filter { it.manifest?.id == overrideId },
        )
    }

    /**
     * A client whose streams need a proof-of-origin token must ask for them as whoever the token
     * was minted for.
     *
     * The token is minted against the anonymous visitor session — every watch page this app fetches
     * is anonymous, which the extractor reports as `authenticated=false`. WEB_REMIX then sent its
     * player request as the signed-in account, because its manifest says cookies=true and
     * authentication=OPTIONAL. A token belonging to one session and a request made as another do
     * not agree, and the CDN refuses the resulting URL.
     *
     * The catalogue provides its own control case. Two clients require a token on their streams:
     *
     *   TVHTML5_SIMPLY  cookies=false  authentication=UNSUPPORTED  token sent  plays
     *   WEB_REMIX       cookies=true   authentication=OPTIONAL     token sent  403, every time
     *
     * They differ in nothing else that matters, so WEB_REMIX is aligned with the one that works:
     * no cookies, no authentication, no login. Clients that need no token keep their own identity
     * untouched — nothing here is a preference about signing in, only about not signing a request
     * with credentials the token in it does not know about.
     *
     * The cost is that this client stops being able to reach anything that needs an account. It
     * could reach nothing at all before.
     */
    private fun matchTokenSession(candidate: SelectedClient): SelectedClient {
        val manifest = candidate.manifest ?: return candidate
        val poTokens = manifest.poTokens
        if (poTokens.gvs.requirement != PoTokenRequirement.REQUIRED) return candidate

        /*
         * The manifest carries its own copy of the client and validates the two against each other
         * on construction: it requires (authentication != UNSUPPORTED) == client.loginSupported.
         * Changing one and not the other throws, which is how the first attempt at this was caught
         * before it reached a device.
         */
        val anonymous =
            candidate.client.copy(
                loginSupported = false,
                loginRequired = false,
            )

        return candidate.copy(
            client = anonymous,
            manifest =
                manifest.copy(
                    client = anonymous,
                    authentication = AuthenticationPolicy.UNSUPPORTED,
                    request = manifest.request.copy(cookies = false),
                    // The token has to travel with the player request as well: a URL minted by a
                    // request that did not carry it is refused whatever is appended afterwards.
                    poTokens = poTokens.copy(player = poTokens.gvs),
                ),
        )
    }
}
