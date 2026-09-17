package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.ContentHints
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
        val bound = selection.copy(candidates = selection.candidates.map(::bindPlayerPoToken))
        val overrideId = request.hints.playbackClientOverrideId ?: return bound
        return bound.copy(
            candidates = bound.candidates.filter { it.manifest?.id == overrideId },
        )
    }

    /**
     * Send the proof-of-origin token with the player request, not only on the stream URL.
     *
     * The WEB clients ship declaring `player = NONE` and `gvs = REQUIRED`, which means the player
     * request goes out anonymous and the token is appended to the media URL afterwards. YouTube no
     * longer accepts that: a URL minted by a player request that did not itself carry the token is
     * refused at the CDN whatever is appended to it later. In a capture of ordinary listening, every
     * WEB_REMIX stream came back 403 — twenty of them — and every one rolled over to another client.
     * The token was there the whole time; it was fetched and then not sent where it counts.
     *
     * So the player rule is raised to match the gvs rule the same manifest already declares: same
     * binding, same providers, same bypass, only now required in both places. Nothing is invented —
     * if a client does not ask for a token on its media URLs, it is left exactly as it is.
     *
     * This is the only thing done to the manifest, deliberately. A client's identity — its cookies,
     * its authentication policy, its login support — is never rewritten here.
     *
     * That was tried, and it was wrong. The reasoning ran: the token is minted against the anonymous
     * visitor session, TVHTML5_SIMPLY is anonymous and plays, WEB_REMIX signs as the account and
     * 403s, so make WEB_REMIX anonymous too. On a device it did not fix the 403, and it took the
     * account down with it: age-restricted tracks began failing with a demand to confirm sign-in,
     * because an anonymous request cannot carry the account that is allowed to play them, and
     * re-linking the account changed nothing since the request no longer used it. Whatever is wrong
     * with WEB_REMIX, stripping its identity is not the fix, and the account is not the price.
     */
    private fun bindPlayerPoToken(candidate: SelectedClient): SelectedClient {
        val manifest = candidate.manifest ?: return candidate
        val poTokens = manifest.poTokens
        if (poTokens.gvs.requirement != PoTokenRequirement.REQUIRED) return candidate
        if (poTokens.player.requirement == PoTokenRequirement.REQUIRED) return candidate

        return candidate.copy(
            manifest = manifest.copy(poTokens = poTokens.copy(player = poTokens.gvs)),
        )
    }
}
