package com.nikhil.yt.playback

import com.nikhil.yt.constants.AudioClientOrder

/**
 * Select VISIONOS for the first uncached stream while the independent Web
 * session prepares. Do not mutate persisted preferences or suppress bounded
 * fallback if VISIONOS cannot play the requested song.
 */
internal object AudioWebStartupClientPolicy {
    fun effectiveOrder(configuredOrder: List<String>, webReady: Boolean): List<String> {
        if (webReady || configuredOrder.firstOrNull() == AudioClientOrder.VISIONOS) {
            return configuredOrder
        }
        return listOf(AudioClientOrder.VISIONOS) +
            configuredOrder.filterNot { it == AudioClientOrder.VISIONOS }
    }
}
