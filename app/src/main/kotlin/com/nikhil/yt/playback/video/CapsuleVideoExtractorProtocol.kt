/*
 * Capsule MUSIC
 * Stable IPC/domain protocol for the optional VIDEO backend.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import android.os.Bundle
import com.nikhil.yt.constants.CapsuleVideoQuality
import com.nikhil.yt.innertube.video.CapsuleVideoDelivery

data class CapsuleResolvedVideo(
    val videoId: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val qualityLabel: String,
    val width: Int,
    val height: Int,
    val videoItag: Int = -1,
    val audioItag: Int = -1,
    val expiresAtMs: Long,
    val backend: String = "newpipe",
    val delivery: CapsuleVideoDelivery = CapsuleVideoDelivery.PROGRESSIVE,
    val maxHeight: Int = 720,
    val dashManifest: String? = null,
    val userAgent: String? = null,
) {
    val adaptive: Boolean
        get() = !audioUrl.isNullOrBlank()
}

enum class CapsuleVideoFailure {
    UNAVAILABLE,
    NETWORK,
    EXTRACTOR,
    RATE_LIMITED,
    BOT_BLOCKED,
    STREAM_EXPIRED,
    REMOTE_PROCESS_DIED,
    CANCELLED,
    UNKNOWN,
}

internal object CapsuleVideoIpc {
    const val METHOD_RESOLVE = "resolve"
    const val METHOD_CANCEL = "cancel"
    const val METHOD_INVALIDATE = "invalidate"
    const val EXTRA_REQUEST_ID = "request_id"
    const val REQUEST_TIMEOUT_MS = 20_000L
    const val EXTRA_ROUTE_TIMINGS = "route_timings"

    const val EXTRA_VIDEO_ID = "video_id"
    const val EXTRA_QUALITY = "quality"
    const val EXTRA_MUXED_ONLY = "muxed_only"
    const val EXTRA_FAILED_BACKEND = "failed_backend"
    const val EXTRA_REFRESH = "refresh"
    const val EXTRA_BACKEND = "backend"
    const val EXTRA_DELIVERY = "delivery"
    const val EXTRA_MAX_HEIGHT = "max_height"
    const val EXTRA_DASH_MANIFEST = "dash_manifest"
    const val EXTRA_USER_AGENT = "user_agent"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_VIDEO_URL = "video_url"
    const val EXTRA_AUDIO_URL = "audio_url"
    const val EXTRA_QUALITY_LABEL = "quality_label"
    const val EXTRA_WIDTH = "width"
    const val EXTRA_HEIGHT = "height"
    const val EXTRA_VIDEO_ITAG = "video_itag"
    const val EXTRA_AUDIO_ITAG = "audio_itag"
    const val EXTRA_EXPIRES_AT = "expires_at"
    const val EXTRA_FAILURE = "failure"
    const val EXTRA_MESSAGE = "message"

    fun qualityToWire(quality: CapsuleVideoQuality): String = quality.name

    fun qualityFromWire(raw: String?): CapsuleVideoQuality =
        runCatching { CapsuleVideoQuality.valueOf(raw.orEmpty()) }
            .getOrDefault(CapsuleVideoQuality.AUTO)

    fun successBundle(resolved: CapsuleResolvedVideo): Bundle =
        Bundle().apply {
            putBoolean(EXTRA_SUCCESS, true)
            putString(EXTRA_VIDEO_ID, resolved.videoId)
            putString(EXTRA_VIDEO_URL, resolved.videoUrl)
            putString(EXTRA_AUDIO_URL, resolved.audioUrl)
            putString(EXTRA_QUALITY_LABEL, resolved.qualityLabel)
            putInt(EXTRA_WIDTH, resolved.width)
            putInt(EXTRA_HEIGHT, resolved.height)
            putInt(EXTRA_VIDEO_ITAG, resolved.videoItag)
            putInt(EXTRA_AUDIO_ITAG, resolved.audioItag)
            putLong(EXTRA_EXPIRES_AT, resolved.expiresAtMs)
            putString(EXTRA_BACKEND, resolved.backend)
            putString(EXTRA_DELIVERY, resolved.delivery.name)
            putInt(EXTRA_MAX_HEIGHT, resolved.maxHeight)
            putString(EXTRA_DASH_MANIFEST, resolved.dashManifest)
            putString(EXTRA_USER_AGENT, resolved.userAgent)
        }

    fun failureBundle(
        failure: CapsuleVideoFailure,
        message: String?,
    ): Bundle =
        Bundle().apply {
            putBoolean(EXTRA_SUCCESS, false)
            putString(EXTRA_FAILURE, failure.name)
            putString(EXTRA_MESSAGE, message)
        }
}
