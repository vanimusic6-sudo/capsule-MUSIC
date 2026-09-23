package com.nikhil.yt.playback

/**
 * One bounded CDN recovery episode per track. This is NOT a YouTube bot/rate-limit
 * fallback: callers must classify those failures before invoking this plan.
 *
 * HTTP 403/410 already gets one same-link retry in AudioChunkedDataSource.
 * An actual transport failure does not, so it gets one same-link reconnect here.
 * Only after that do we retire the extraction client for this track and obtain
 * a different client's freshly signed URL. No client carousel after that.
 */
internal enum class AudioCdnRecoveryAction {
    RETRY_SAME_URL,
    TRY_NEXT_CLIENT,
    SKIP_TRACK,
}

internal class AudioCdnRecoveryPlan {
    private enum class Stage { INITIAL, SAME_URL_RETRIED, NEXT_CLIENT_TRIED }
    private val stages = LinkedHashMap<String, Stage>()

    fun onFailure(mediaId: String, signedUrlRejected: Boolean): AudioCdnRecoveryAction {
        val stage = stages[mediaId] ?: Stage.INITIAL
        val action = when (stage) {
            Stage.INITIAL ->
                if (signedUrlRejected) AudioCdnRecoveryAction.TRY_NEXT_CLIENT
                else AudioCdnRecoveryAction.RETRY_SAME_URL
            Stage.SAME_URL_RETRIED -> AudioCdnRecoveryAction.TRY_NEXT_CLIENT
            Stage.NEXT_CLIENT_TRIED -> AudioCdnRecoveryAction.SKIP_TRACK
        }
        stages[mediaId] = when (action) {
            AudioCdnRecoveryAction.RETRY_SAME_URL -> Stage.SAME_URL_RETRIED
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
