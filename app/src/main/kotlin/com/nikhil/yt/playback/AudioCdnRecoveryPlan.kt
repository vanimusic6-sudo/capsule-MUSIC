package com.nikhil.yt.playback

internal enum class AudioCdnRecoveryAction {
    RETRY_SAME_URL, TRY_FRESH_URL_SAME_CLIENT, SKIP_TRACK,
}

/**
 * One recovery for a genuinely transient transport failure or expired (410)
 * URL. Access refusals (401/403), bot challenges and rate limits terminate
 * BEFORE this policy. Never spend an additional client/transport budget.
 */
internal class AudioCdnRecoveryPlan {
    private val recovered = LinkedHashSet<String>()

    fun onFailure(mediaId: String, signedUrlRejected: Boolean, unresponsiveOpen: Boolean = false): AudioCdnRecoveryAction {
        if (!recovered.add(mediaId)) return AudioCdnRecoveryAction.SKIP_TRACK
        while (recovered.size > 128) recovered.remove(recovered.first())
        return if (signedUrlRejected || unresponsiveOpen) AudioCdnRecoveryAction.TRY_FRESH_URL_SAME_CLIENT
            else AudioCdnRecoveryAction.RETRY_SAME_URL
    }

    fun recoveryUsed(mediaId: String): Boolean = mediaId in recovered
    fun reset(mediaId: String) { recovered.remove(mediaId) }
    fun clear() { recovered.clear() }
}
