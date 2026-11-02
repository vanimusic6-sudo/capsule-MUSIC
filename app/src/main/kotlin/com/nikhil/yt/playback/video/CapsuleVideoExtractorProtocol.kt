/*
 * Capsule MUSIC
 * Stable IPC/domain protocol for the optional VIDEO backend.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import android.os.Bundle
import com.nikhil.yt.constants.CapsuleVideoQuality
import com.nikhil.yt.innertube.video.CapsuleVideoDelivery
import com.nikhil.yt.innertube.video.CapsuleSabrVideoSpec

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
    val sabrSpec: CapsuleSabrVideoSpec? = null,
    /** Main-process maintained extractor only; no credentials enter legacy parser IPC. */
    val requestHeaders: Map<String, String> = emptyMap(),
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
    /** Local isolated-process bootstrap only; never fetches a YouTube URL. */
    const val METHOD_PREPARE = "prepare"
    const val METHOD_CANCEL = "cancel"
    const val METHOD_INVALIDATE = "invalidate"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_SESSION_KEY = "session_key"
    // All five route budgets total 32s. Leave room for initialization,
    // Binder delivery and matching instead of cutting the final routes off.
    const val EXTRACTION_TIMEOUT_MS = 36_000L
    const val REQUEST_TIMEOUT_MS = 40_000L
    const val SONG_TIMEOUT_MS = 55_000L
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
    const val EXTRA_SABR = "sabr_session"
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

    // Extractors work on third-party metadata. Never let a malformed
    // URL/manifest or oversized parcel escape to the main player process.
    // This guards Binder/Media3, not third-party code executing in our UID.
    fun isSafeStreamUrl(raw: String?): Boolean =
        com.nikhil.yt.innertube.video.VideoStreamValidation.isSupportedUrl(raw)

    fun successBundle(resolved: CapsuleResolvedVideo): Bundle {
        if (!com.nikhil.yt.innertube.video.VideoStreamValidation.accepts(
                resolved.videoUrl, resolved.audioUrl, resolved.dashManifest, resolved.userAgent, resolved.delivery, resolved.qualityLabel,
            ) || (resolved.delivery == CapsuleVideoDelivery.SABR) !=
                (resolved.sabrSpec?.isValid() == true)) {
            return failureBundle(CapsuleVideoFailure.UNAVAILABLE, "Unsupported VIDEO extractor response")
        }
        return Bundle().apply {
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
            resolved.sabrSpec?.let { spec ->
                putBundle(EXTRA_SABR, Bundle().apply {
                    putString("ustreamer", spec.ustreamerConfigBase64)
                    putInt("v_itag", spec.videoItag)
                    putLong("v_lmt", spec.videoLastModified)
                    putLong("v_length", spec.videoLength)
                    putInt("a_itag", spec.audioItag)
                    putLong("a_lmt", spec.audioLastModified)
                    putLong("a_length", spec.audioLength)
                    putInt("client_name", spec.clientName)
                    putString("client_version", spec.clientVersion)
                    putLong("duration", spec.durationMs)
                    putString("pot", spec.poTokenBase64)
                    putString("v_xtags", spec.videoXtags)
                    putString("a_xtags", spec.audioXtags)
                    putString("origin", spec.requestOrigin)
                })
            }
        }
    }

    /** Revalidate the response in the consumer process before caching or switching players. */
    fun resolvedFromBundle(
        bundle: Bundle,
        expectedVideoId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): CapsuleResolvedVideo? {
        if (!bundle.getBoolean(EXTRA_SUCCESS, false)) return null
        val url = bundle.getString(EXTRA_VIDEO_URL)
        val audio = bundle.getString(EXTRA_AUDIO_URL)?.takeIf { it.isNotBlank() }
        val id = bundle.getString(EXTRA_VIDEO_ID).orEmpty().ifBlank { expectedVideoId }
        val label = bundle.getString(EXTRA_QUALITY_LABEL).orEmpty().ifBlank { "VIDEO" }
        val manifest = bundle.getString(EXTRA_DASH_MANIFEST)
        val agent = bundle.getString(EXTRA_USER_AGENT)
        val expiry = bundle.getLong(EXTRA_EXPIRES_AT, nowMs + 3_600_000L)
        val rawDelivery = bundle.getString(EXTRA_DELIVERY).orEmpty()
        val delivery = if (rawDelivery.isBlank()) CapsuleVideoDelivery.PROGRESSIVE else
            CapsuleVideoDelivery.entries.firstOrNull { it.name == rawDelivery } ?: return null
        val sabr = bundle.getBundle(EXTRA_SABR)?.let { payload ->
            CapsuleSabrVideoSpec(
                ustreamerConfigBase64 = payload.getString("ustreamer").orEmpty(),
                videoItag = payload.getInt("v_itag"), videoLastModified = payload.getLong("v_lmt"),
                videoLength = payload.getLong("v_length"),
                audioItag = payload.getInt("a_itag"), audioLastModified = payload.getLong("a_lmt"),
                audioLength = payload.getLong("a_length"),
                clientName = payload.getInt("client_name"),
                clientVersion = payload.getString("client_version").orEmpty(),
                durationMs = payload.getLong("duration"),
                poTokenBase64 = payload.getString("pot").orEmpty(),
                videoXtags = payload.getString("v_xtags"),
                audioXtags = payload.getString("a_xtags"),
                requestOrigin = payload.getString("origin"),
            )
        }
        if (id != expectedVideoId ||
            !com.nikhil.yt.innertube.video.VideoStreamValidation.accepts(url, audio, manifest, agent, delivery, label) ||
            !com.nikhil.yt.innertube.video.VideoStreamValidation.isFresh(expiry, nowMs) ||
            (delivery == CapsuleVideoDelivery.SABR) != (sabr?.isValid() == true)) return null
        return CapsuleResolvedVideo(
            videoId = id, videoUrl = requireNotNull(url), audioUrl = audio,
            qualityLabel = label,
            width = bundle.getInt(EXTRA_WIDTH, 0), height = bundle.getInt(EXTRA_HEIGHT, 0),
            videoItag = bundle.getInt(EXTRA_VIDEO_ITAG, -1), audioItag = bundle.getInt(EXTRA_AUDIO_ITAG, -1),
            expiresAtMs = expiry, backend = bundle.getString(EXTRA_BACKEND) ?: "newpipe",
            delivery = delivery, maxHeight = bundle.getInt(EXTRA_MAX_HEIGHT, 720).coerceIn(1, 720),
            dashManifest = manifest, userAgent = agent, sabrSpec = sabr,
        )
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
