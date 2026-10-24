package com.nikhil.yt.playback

import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenRequirement
import com.nikhil.yt.constants.AudioClientOrder

/** Temporary effective order only. Never rewrite the user's persisted priority. */
internal object AudioWebStartupClientPolicy {
    fun prepareOrder(configuredOrder: List<String>, webReady: Boolean, requestWarmup: () -> Unit): List<String> {
        if (!webReady && requiresToken(configuredOrder.firstOrNull().orEmpty())) requestWarmup()
        return effectiveOrder(configuredOrder, webReady)
    }

    fun requiresToken(profileId: String): Boolean {
        val tokens = PlaybackClientCatalog.findManifest(profileId)?.poTokens ?: return false
        return tokens.player.requirement == PoTokenRequirement.REQUIRED ||
            tokens.gvs.requirement == PoTokenRequirement.REQUIRED
    }

    fun effectiveOrder(configuredOrder: List<String>, webReady: Boolean): List<String> {
        if (webReady || !requiresToken(configuredOrder.firstOrNull().orEmpty())) return configuredOrder
        // 1.02 is probe-only and can stall in clean sessions. Prefer the catalog's
        // automatic 0.1 profile for this temporary cold-token window only.
        val vision = listOf(AudioClientOrder.VISIONOS_0_1, AudioClientOrder.VISIONOS)
            .filter { it in configuredOrder }
        return vision + configuredOrder.filterNot { it in vision }
    }
}
