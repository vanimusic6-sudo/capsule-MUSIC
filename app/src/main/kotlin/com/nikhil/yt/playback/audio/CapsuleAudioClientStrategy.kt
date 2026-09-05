package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.models.YouTubeClient

/** Keep an explicit choice strict even when the library excludes that client. */
internal object CapsuleAudioClientStrategy : ClientFallbackStrategy {
    private val delegate = ContentAwareFallbackStrategy()

    override fun resolveClients(hints: ContentHints): List<YouTubeClient> =
        selectClients(ClientSelectionRequest(hints = hints, authenticated = false))
            .candidates.map { it.client }

    override fun selectClients(request: ClientSelectionRequest): ClientSelectionResult {
        val selection = delegate.selectClients(request)
        val overrideId = request.hints.playbackClientOverrideId
        return selection.copy(
            candidates = selection.candidates.filter { it.manifest?.id == overrideId },
        )
    }
}
