package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.models.YouTubeClient

/**
 * Keep explicit playback choices strict without breaking generic InnerTubeX calls.
 *
 * Capsule playback always supplies [ContentHints.playbackClientOverrideId]. In that
 * mode the selected profile is authoritative: if the library excludes it, return no
 * candidates rather than silently rotating identities. Internal library/prewarm calls
 * that do not carry an override keep the maintained content-aware fallback behavior.
 *
 * Nothing here rewrites a manifest. Two attempts to fix WEB_REMIX's 403s from this seat both made
 * things worse, so the rule is now explicit: pick a client, never redescribe one.
 *
 * The first raised WEB_REMIX's player poToken rule to match its gvs rule. The catalogue declares
 * `player = NONE` and `gvs = REQUIRED, binding = VIDEO_ID` — a token bound to a video id, which is
 * the right shape for a media URL and the wrong shape for the player request that mints it. The
 * second went further and stripped the client's cookies and login to match the anonymous session
 * the token seemed to belong to. Neither fixed a single 403, and together they cost the account:
 * age-restricted tracks began demanding a sign-in that was already there, and re-linking it did
 * nothing, because the request had stopped being able to carry it.
 *
 * A manifest is the library's description of a client that really exists. Editing it does not
 * change what YouTube will accept from that client; it only makes this app describe it wrongly.
 */
internal object CapsuleAudioClientStrategy : ClientFallbackStrategy {
    private val delegate = ContentAwareFallbackStrategy()

    override fun resolveClients(hints: ContentHints): List<YouTubeClient> =
        selectClients(ClientSelectionRequest(hints = hints, authenticated = false))
            .candidates.map { it.client }

    override fun selectClients(request: ClientSelectionRequest): ClientSelectionResult {
        val selection = delegate.selectClients(request)
        val overrideId = request.hints.playbackClientOverrideId ?: return selection
        return selection.copy(
            candidates = selection.candidates.filter { it.manifest?.id == overrideId },
        )
    }
}
