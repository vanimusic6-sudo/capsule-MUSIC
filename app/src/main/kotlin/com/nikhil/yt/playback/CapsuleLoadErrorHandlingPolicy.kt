@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

private const val CAPSULE_AUDIO_CACHE_PREFIX = "capsule:audio:"
private val REJECTED_SIGNED_URL_STATUS_CODES = setOf(403, 410)

/**
 * Audio CDN URLs are signed/tokenized and a freshly resolved GVS URL can be rejected
 * transiently before the same URL becomes usable. Device captures show both one-shot
 * 403s and a double-403 that succeeds roughly one second later without a fresh resolve.
 * Use a tiny, bounded propagation backoff instead of immediately spending another
 * /player request and rotating client identity.
 *
 * 403 gets two delayed same-URL retries (250 ms, then 1 s). 410 remains tighter because
 * it more often represents a genuinely gone URL: one 250 ms retry, then fresh resolve.
 * 429 is an explicit throttle signal and never retries the same URL.
 *
 * null means: use Media3's normal policy for this load.
 */
internal fun audioCdnRejectedRetryDelayMs(
    cacheKey: String?,
    httpStatusCode: Int?,
    errorCount: Int,
): Long? {
    if (cacheKey?.startsWith(CAPSULE_AUDIO_CACHE_PREFIX) != true) return null
    if (httpStatusCode == 429) return C.TIME_UNSET
    if (httpStatusCode !in REJECTED_SIGNED_URL_STATUS_CODES) return null

    return when (httpStatusCode) {
        403 ->
            when (errorCount) {
                1 -> 250L
                2 -> 1_000L
                else -> C.TIME_UNSET
            }
        410 -> if (errorCount <= 1) 250L else C.TIME_UNSET
        else -> null
    }
}

internal class CapsuleLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): Long {
        val statusCode =
            generateSequence(loadErrorInfo.exception as Throwable?) { it.cause }
                .take(8)
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
                ?.responseCode

        val capsuleDecision =
            audioCdnRejectedRetryDelayMs(
                cacheKey = loadErrorInfo.loadEventInfo.dataSpec.key,
                httpStatusCode = statusCode,
                errorCount = loadErrorInfo.errorCount,
            )
        return capsuleDecision ?: super.getRetryDelayMsFor(loadErrorInfo)
    }
}
