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
