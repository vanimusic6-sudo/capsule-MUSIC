package com.nikhil.yt.playback

/**
 * One deterministic CDN recovery episode per track. The caller must handle
 * offline, HTTP 429 and server bot challenges BEFORE entering this plan.
 *
 * A 403/410 does not reissue the same signed URL or silently obtain another
 * URL from the same client. Repeated HTTP refusals and unresponsive opens are
 * immediately handed to the next eligible extraction client. Only a proven
 * transient transport failure may reconnect once.
 *
 * On the second distinct Direct failure the bounded hybrid plan gets one
 * chance to negotiate SABR. A failed SABR session may return once to Direct,
 * then a further failure terminates this recovery episode.
 *
 * Advance state before asynchronous Media3 retries to prevent callback loops.
 */
internal enum class AudioCdnRecoveryAction {
    RETRY_SAME_URL,
    TRY_NEXT_CLIENT,
    SKIP_TRACK,
}

internal class AudioCdnRecoveryPlan {
    private enum class Stage {
        INITIAL, RECONNECTED, ALTERNATE_TRIED, HYBRID_TRIED, SABR_FAILED,
    }
    private val stages = LinkedHashMap<String, Stage>()

    fun onFailure(
        mediaId: String,
        signedUrlRejected: Boolean,
        unresponsiveOpen: Boolean = false,
    ): AudioCdnRecoveryAction {
        val stage = stages[mediaId] ?: Stage.INITIAL
        val action = when (stage) {
            Stage.INITIAL -> if (signedUrlRejected || unresponsiveOpen) {
                AudioCdnRecoveryAction.TRY_NEXT_CLIENT
            } else {
                AudioCdnRecoveryAction.RETRY_SAME_URL
            }
            Stage.RECONNECTED -> AudioCdnRecoveryAction.TRY_NEXT_CLIENT
            // A second distinct CDN failure can now promote the independently
            // negotiated SABR transport, rather than ending the track before
            // the hybrid route has ever had a chance.
            Stage.ALTERNATE_TRIED -> AudioCdnRecoveryAction.TRY_NEXT_CLIENT
            Stage.HYBRID_TRIED, Stage.SABR_FAILED -> AudioCdnRecoveryAction.SKIP_TRACK
        }
        stages[mediaId] = when (action) {
            AudioCdnRecoveryAction.RETRY_SAME_URL -> Stage.RECONNECTED
            AudioCdnRecoveryAction.TRY_NEXT_CLIENT ->
                if (stage == Stage.ALTERNATE_TRIED) Stage.HYBRID_TRIED else Stage.ALTERNATE_TRIED
            AudioCdnRecoveryAction.SKIP_TRACK -> stage
        }
        while (stages.size > 128) stages.remove(stages.keys.first())
        return action
    }

    /**
     * SABR is an independent transport. One failed UMP session can return to a
     * remaining Direct profile, but never retry the same SABR session in a loop.
     * This does not reset the direct CDN failure budget.
     */
    fun onSabrFailure(mediaId: String): AudioCdnRecoveryAction {
        val stage = stages[mediaId] ?: Stage.INITIAL
        if (stage == Stage.SABR_FAILED) return AudioCdnRecoveryAction.SKIP_TRACK
        stages[mediaId] = Stage.SABR_FAILED
        while (stages.size > 128) stages.remove(stages.keys.first())
        return AudioCdnRecoveryAction.TRY_NEXT_CLIENT
    }

    fun nextClientAlreadyTried(mediaId: String): Boolean =
        (stages[mediaId] ?: Stage.INITIAL) in setOf(
            Stage.ALTERNATE_TRIED, Stage.HYBRID_TRIED, Stage.SABR_FAILED,
        )

    fun reset(mediaId: String) { stages.remove(mediaId) }

    fun clear() { stages.clear() }
}
