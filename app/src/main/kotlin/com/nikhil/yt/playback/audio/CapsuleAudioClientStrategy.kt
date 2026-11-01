package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
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
 * Client manifests, account cookies and token-binding rules belong to InnerTubeX.
 * Capsule chooses among pinned manifests without rewriting their authentication
 * or PoToken requirements. A video-bound GVS token is not a player-request token.
 */
internal object CapsuleAudioClientStrategy : ClientFallbackStrategy {
    private val delegate = ContentAwareFallbackStrategy()

    fun profileId(extractedProfileId: String): String =
        PlaybackClientCatalog.manifestIdFromProfileId(extractedProfileId)
            ?: extractedProfileId.substringBefore('@')

    /** A wire name such as VISIONOS would also exclude the distinct 0.1 profile. */
    fun exclusions(profileIds: Set<String>): Set<String> = profileIds.flatMap { id ->
        PlaybackClientCatalog.findManifest(id)?.let { manifest ->
            listOf("${manifest.id}__po", "${manifest.id}__nopo")
        } ?: listOf(id)
    }.toSet()

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
