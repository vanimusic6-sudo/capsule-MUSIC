@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nikhil.yt.utils.GlobalLog
import timber.log.Timber

private const val CAPSULE_AUDIO_CACHE_PREFIX = "capsule:audio:"
private val REJECTED_SIGNED_URL_STATUS_CODES = setOf(403, 410)

/**
 * Audio CDN URLs are signed/tokenized and can be rejected transiently even though
 * an unchanged retry succeeds. Device captures show both one-shot 403s and a
 * double-403 that succeeds later without a fresh /player resolve.
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

/**
 * ResolvingDataSource reports load events using its outer DataSpec, whose key can still be
 * the plain media id. The HTTP exception, however, carries the resolved DataSpec that was
 * actually opened by OkHttp and therefore contains Capsule's format-aware audio cache key.
 * Prefer that inner key so our bounded 403/410 backoff is applied to the real CDN request
 * instead of silently falling through to Media3's immediate default retry.
 */
internal fun audioCdnRetryCacheKey(
    outerCacheKey: String?,
    resolvedFailureCacheKey: String?,
): String? =
    resolvedFailureCacheKey
        ?.takeIf { it.startsWith(CAPSULE_AUDIO_CACHE_PREFIX) }
        ?: outerCacheKey

internal class CapsuleLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): Long {
        val httpFailure =
            generateSequence(loadErrorInfo.exception as Throwable?) { it.cause }
                .take(8)
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()

        val resolvedKey =
            audioCdnRetryCacheKey(
                outerCacheKey = loadErrorInfo.loadEventInfo.dataSpec.key,
                resolvedFailureCacheKey = httpFailure?.dataSpec?.key,
            )
        val capsuleDecision =
            audioCdnRejectedRetryDelayMs(
                cacheKey = resolvedKey,
                httpStatusCode = httpFailure?.responseCode,
                errorCount = loadErrorInfo.errorCount,
            )
        if (GlobalLog.isEnabled && httpFailure != null && capsuleDecision != null) {
            Timber.tag("AudioCDN").d(
                "cdn-retry-policy status=%d errorCount=%d delayMs=%d stop=%s id=%s",
                httpFailure.responseCode,
                loadErrorInfo.errorCount,
                if (capsuleDecision == C.TIME_UNSET) -1L else capsuleDecision,
                capsuleDecision == C.TIME_UNSET,
                resolvedKey?.take(64) ?: "none",
            )
        }
        return capsuleDecision ?: super.getRetryDelayMsFor(loadErrorInfo)
    }
}
