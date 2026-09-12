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
 * A 403/410 from googlevideo rejects the current signed URL generation.
 *
 * Step43 device captures finally separated this from transient transport noise: one
 * generation failed at ages ~1.3 s, ~1.8 s and ~3.4 s with complete PoToken/n/signature
 * and request headers, while a fresh /player generation for the same mediaId/itag opened
 * successfully. Retrying the rejected generation only creates more 403s and delays recovery.
 *
 * Fail the current load immediately so MusicService can invalidate that one PlaybackData
 * generation and perform its bounded fresh resolve. 429 also never retries the same URL.
 * null means: keep Media3's normal policy for unrelated loads/statuses.
 */
internal fun audioCdnRejectedRetryDelayMs(
    cacheKey: String?,
    httpStatusCode: Int?,
    errorCount: Int,
): Long? {
    if (cacheKey?.startsWith(CAPSULE_AUDIO_CACHE_PREFIX) != true) return null
    if (httpStatusCode == 429) return C.TIME_UNSET
    if (httpStatusCode in REJECTED_SIGNED_URL_STATUS_CODES) return C.TIME_UNSET
    return null
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
