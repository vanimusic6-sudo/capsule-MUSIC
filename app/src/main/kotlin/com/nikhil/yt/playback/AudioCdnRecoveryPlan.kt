package com.nikhil.yt.playback

/**
 * One bounded CDN recovery episode per track. This is NOT a YouTube bot/rate-limit
 * fallback: callers must classify those failures before invoking this plan.
 *
 * HTTP 403/410 already gets one same-link retry in AudioChunkedDataSource.
 * A transient transport failure gets one same-link reconnect here, EXCEPT an
 * open that exhausted its network timeout before receiving any bytes. Retrying
 * that precise route can cost another full timeout, so it requests a fresh URL once through the same policy before
 * retiring a client. A CDN route failure alone does not prove that client is broken. No client carousel after that.
 */
internal enum class AudioCdnRecoveryAction {
    RETRY_SAME_URL,
    RESOLVE_FRESH_URL,
    TRY_NEXT_CLIENT,
    SKIP_TRACK,
}

internal class AudioCdnRecoveryPlan {
    private enum class Stage { INITIAL, SAME_URL_RETRIED, FRESH_URL_TRIED, NEXT_CLIENT_TRIED }
    private val stages = LinkedHashMap<String, Stage>()

    fun onFailure(
        mediaId: String,
        signedUrlRejected: Boolean,
        unresponsiveOpen: Boolean = false,
    ): AudioCdnRecoveryAction {
        val stage = stages[mediaId] ?: Stage.INITIAL
        val action = when (stage) {
            // A 403/410 already used the chunk layer's one same-link retry.
            // A no-byte open timeout used its entire connection/TLS budget:
            // repeating that precise route immediately adds another long
            // silence without any evidence that the link itself changed.
            Stage.INITIAL ->
                if (signedUrlRejected || unresponsiveOpen) AudioCdnRecoveryAction.RESOLVE_FRESH_URL
                else AudioCdnRecoveryAction.RETRY_SAME_URL
            Stage.SAME_URL_RETRIED, Stage.FRESH_URL_TRIED -> AudioCdnRecoveryAction.TRY_NEXT_CLIENT
            Stage.NEXT_CLIENT_TRIED -> AudioCdnRecoveryAction.SKIP_TRACK
        }
        stages[mediaId] = when (action) {
            AudioCdnRecoveryAction.RETRY_SAME_URL -> Stage.SAME_URL_RETRIED
            AudioCdnRecoveryAction.RESOLVE_FRESH_URL -> Stage.FRESH_URL_TRIED
            AudioCdnRecoveryAction.TRY_NEXT_CLIENT,
            AudioCdnRecoveryAction.SKIP_TRACK -> Stage.NEXT_CLIENT_TRIED
        }
        // Only the current and a bounded handful of recently failed IDs matter.
        while (stages.size > 128) stages.remove(stages.keys.first())
        return action
    }

    fun nextClientAlreadyTried(mediaId: String): Boolean =
        stages[mediaId] == Stage.NEXT_CLIENT_TRIED

    fun reset(mediaId: String) { stages.remove(mediaId) }

    fun clear() { stages.clear() }
}
