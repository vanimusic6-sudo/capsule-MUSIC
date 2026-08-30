/*
 * Capsule MUSIC
 * Stable IPC/domain protocol for the optional VIDEO backend.
 *
 * Keep this file free of NewPipe classes. MusicService depends only on these
 * plain data types, so upstream extractor changes cannot leak through the app.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import android.os.Bundle
import com.nikhil.yt.constants.CapsuleVideoQuality

/** A resolved stream set returned by the isolated VIDEO backend. */
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
    const val ACTION_RESOLVE = "com.nikhil.yt.video.RESOLVE"
    const val ACTION_CANCEL = "com.nikhil.yt.video.CANCEL"
    const val ACTION_RESULT = "com.nikhil.yt.video.RESULT"

    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_VIDEO_ID = "video_id"
    const val EXTRA_QUALITY = "quality"
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

    fun successBundle(requestId: String, resolved: CapsuleResolvedVideo): Bundle =
        Bundle().apply {
            putString(EXTRA_REQUEST_ID, requestId)
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
        }

    fun failureBundle(
        requestId: String,
        failure: CapsuleVideoFailure,
        message: String?,
    ): Bundle =
        Bundle().apply {
            putString(EXTRA_REQUEST_ID, requestId)
            putBoolean(EXTRA_SUCCESS, false)
            putString(EXTRA_FAILURE, failure.name)
            putString(EXTRA_MESSAGE, message)
        }
}
