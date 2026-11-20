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
 * Advance state before asynchronous Media3 retries to prevent callback loops.
 */
internal enum class AudioCdnRecoveryAction {
    RETRY_SAME_URL,
    TRY_NEXT_CLIENT,
    SKIP_TRACK,
}

internal class AudioCdnRecoveryPlan {
    private enum class Stage { INITIAL, RECONNECTED, ALTERNATE_TRIED }
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
            Stage.ALTERNATE_TRIED -> AudioCdnRecoveryAction.SKIP_TRACK
        }
        stages[mediaId] = when (action) {
            AudioCdnRecoveryAction.RETRY_SAME_URL -> Stage.RECONNECTED
            AudioCdnRecoveryAction.TRY_NEXT_CLIENT,
            AudioCdnRecoveryAction.SKIP_TRACK -> Stage.ALTERNATE_TRIED
        }
        while (stages.size > 128) stages.remove(stages.keys.first())
        return action
    }

    fun nextClientAlreadyTried(mediaId: String): Boolean =
        stages[mediaId] == Stage.ALTERNATE_TRIED

    fun reset(mediaId: String) { stages.remove(mediaId) }

    fun clear() { stages.clear() }
}
