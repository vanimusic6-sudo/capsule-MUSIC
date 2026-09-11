@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

private const val CAPSULE_AUDIO_CACHE_PREFIX = "capsule:audio:"
private val REJECTED_SIGNED_URL_STATUS_CODES = setOf(403, 410)

/**
 * Audio CDN URLs are signed and can occasionally be rejected even after a successful
 * WEB_REMIX resolve. Keep exactly one immediate same-URL retry for 403/410 because
 * captures show that a transient rejection can succeed on the next open. A second
 * rejection is fatal for this load so MusicService can invalidate the URL and do its
 * bounded fresh resolve instead of letting Media3 hammer the same rejected URL.
 *
 * 429 is different: it is an explicit throttle signal, so the current CDN URL fails
 * immediately and MusicService's rate-limit circuit breaker gets control without a
 * redundant request.
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
    return if (errorCount <= 1) 0L else C.TIME_UNSET
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
