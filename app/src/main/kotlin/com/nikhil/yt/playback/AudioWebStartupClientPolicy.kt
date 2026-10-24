package com.nikhil.yt.playback

import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenRequirement
import com.nikhil.yt.constants.AudioClientOrder

/** Temporary effective order only. Never rewrite the user's persisted priority. */
internal object AudioWebStartupClientPolicy {
    fun requiresToken(profileId: String): Boolean {
        val tokens = PlaybackClientCatalog.findManifest(profileId)?.poTokens ?: return false
        return tokens.player.requirement == PoTokenRequirement.REQUIRED ||
            tokens.gvs.requirement == PoTokenRequirement.REQUIRED
    }

    fun effectiveOrder(configuredOrder: List<String>, webReady: Boolean): List<String> {
        if (webReady || !requiresToken(configuredOrder.firstOrNull().orEmpty())) return configuredOrder
        // These are distinct profiles (1.02 and 0.1), not interchangeable aliases.
        // Their relative order remains the user's choice; eligibility/quarantine is
        // still checked by the resolver before making any player request.
        val vision = configuredOrder.filter {
            it == AudioClientOrder.VISIONOS || it == AudioClientOrder.VISIONOS_0_1
        }
        return vision + configuredOrder.filterNot { it in vision }
    }
}
