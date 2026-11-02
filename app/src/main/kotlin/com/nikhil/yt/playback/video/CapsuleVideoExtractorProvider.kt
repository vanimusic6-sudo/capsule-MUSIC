/*
 * Capsule MUSIC
 * Cross-process boundary for NewPipeExtractor.
 *
 * Runs in :capsule_video. A parser/Rhino/NewPipe crash therefore cannot kill
 * MusicService. The provider is non-exported and accepts only an exact video id
 * plus a quality enum; no account credentials cross this boundary.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.nikhil.yt.innertube.video.CapsuleNewPipeExtractor
import com.nikhil.yt.innertube.video.CapsuleDirectVideoExtractor
import com.nikhil.yt.innertube.video.CapsuleNewPipeFailure
import com.nikhil.yt.innertube.video.CapsuleNewPipeQuality
import com.nikhil.yt.innertube.video.VideoExtractionRequests

class CapsuleVideoExtractorProvider : ContentProvider() {
    // A slow/upstream NewPipe initialization must NEVER crash provider startup.
    // initializeIfNeeded() is called lazily by the isolated VIDEO resolve.
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        val requestId = extras?.getString(CapsuleVideoIpc.EXTRA_REQUEST_ID).orEmpty()
        if (!requestId.matches(Regex("[a-fA-F0-9-]{36}"))) {
            return CapsuleVideoIpc.failureBundle(CapsuleVideoFailure.UNKNOWN, "Invalid VIDEO request id")
        }
        if (method == CapsuleVideoIpc.METHOD_PREPARE) {
            // Crossing this Binder boundary starts the isolated process while
            // the foreground request is still matching a MUSIC clip. No
            // Youtube requests, parsers or player clients are invoked here.
            return Bundle.EMPTY
        }
        if (method == CapsuleVideoIpc.METHOD_CANCEL) {
            VideoExtractionRequests.cancel(requestId)
            return Bundle.EMPTY
        }
        if (method == CapsuleVideoIpc.METHOD_INVALIDATE) {
            extras?.getString(CapsuleVideoIpc.EXTRA_VIDEO_ID)
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                ?.let(CapsuleNewPipeExtractor::invalidate)
            return Bundle.EMPTY
        }
        if (method != CapsuleVideoIpc.METHOD_RESOLVE) {
            return CapsuleVideoIpc.failureBundle(
                CapsuleVideoFailure.UNKNOWN,
                "Unsupported VIDEO extractor method",
            )
        }

        val videoId =
            extras?.getString(CapsuleVideoIpc.EXTRA_VIDEO_ID)
                ?.trim()
                .orEmpty()
        val quality =
            CapsuleVideoIpc.qualityFromWire(
                extras?.getString(CapsuleVideoIpc.EXTRA_QUALITY),
            )
        val muxedOnly = extras?.getBoolean(CapsuleVideoIpc.EXTRA_MUXED_ONLY, false) ?: false

        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            return CapsuleVideoIpc.failureBundle(
                CapsuleVideoFailure.UNAVAILABLE,
                "Invalid YouTube video id",
            )
        }

        // Local-only route preference. It contains a backend name, never an
        // account token, signed URL or track ID. A measured success overwrites
        // it; bot/route-health cooldowns still override it automatically.
        val routePreferences = runCatching {
            context?.getSharedPreferences("capsule_video_route_health", android.content.Context.MODE_PRIVATE)
        }.getOrNull()
        CapsuleNewPipeExtractor.restorePreferredRoute(
            runCatching { routePreferences?.getString("last_successful_backend", null) }.getOrNull()
        )

        val routeTimings = mutableListOf<String>()
        return runCatching {
            VideoExtractionRequests.withRequest(requestId, CapsuleVideoIpc.EXTRACTION_TIMEOUT_MS) {
                extras?.getString(CapsuleVideoIpc.EXTRA_SESSION_KEY)?.let(CapsuleDirectVideoExtractor::beginSession)
                if (extras?.getBoolean(CapsuleVideoIpc.EXTRA_REFRESH, false) == true) {
                    CapsuleNewPipeExtractor.invalidate(videoId)
                }
                CapsuleNewPipeExtractor.resolveWithFallback(
                    videoId,
                    CapsuleNewPipeQuality.valueOf(quality.name),
                    muxedOnly,
                    extras?.getString(CapsuleVideoIpc.EXTRA_FAILED_BACKEND),
                    onAttempt = { route, elapsed, success -> routeTimings += "$route:${elapsed}ms:${if (success) "ok" else "fallback"}" },
                    onRouteFailure = { route, failure ->
                        val kind = CapsuleNewPipeExtractor.classify(failure)
                        // External extractor errors may contain signed media URLs.
                        routeTimings += "$route:$kind"
                        timber.log.Timber.tag("CapsuleVideo").w("VIDEO route=%s failed kind=%s", route, kind)
                    },
                )
            }
        }.fold(
            onSuccess = { resolved ->
                runCatching {
                    routePreferences?.edit()
                        ?.putString("last_successful_backend", resolved.backend)
                        ?.apply()
                }
                CapsuleVideoIpc.successBundle(
                    CapsuleResolvedVideo(
                        videoId = resolved.videoId,
                        videoUrl = resolved.videoUrl,
                        audioUrl = resolved.audioUrl,
                        qualityLabel = resolved.qualityLabel,
                        width = resolved.width,
                        height = resolved.height,
                        videoItag = resolved.videoItag,
                        audioItag = resolved.audioItag,
                        expiresAtMs = resolved.expiresAtMs,
                        backend = resolved.backend,
                        delivery = resolved.delivery,
                        maxHeight = resolved.maxHeight,
                        dashManifest = resolved.dashManifest,
                        userAgent = resolved.userAgent,
                        sabrSpec = resolved.sabrSpec,
                    ),
                )
            },
            onFailure = { throwable ->
                CapsuleVideoIpc.failureBundle(
                    when (CapsuleNewPipeExtractor.classify(throwable)) {
                        CapsuleNewPipeFailure.UNAVAILABLE -> CapsuleVideoFailure.UNAVAILABLE
                        CapsuleNewPipeFailure.NETWORK -> CapsuleVideoFailure.NETWORK
                        CapsuleNewPipeFailure.EXTRACTOR -> CapsuleVideoFailure.EXTRACTOR
                        CapsuleNewPipeFailure.RATE_LIMITED -> CapsuleVideoFailure.RATE_LIMITED
                        CapsuleNewPipeFailure.BOT_BLOCKED -> CapsuleVideoFailure.BOT_BLOCKED
                        CapsuleNewPipeFailure.UNKNOWN -> CapsuleVideoFailure.UNKNOWN
                    },
                    throwable::class.java.simpleName,
                )
            },
        ).also { it.putString(CapsuleVideoIpc.EXTRA_ROUTE_TIMINGS, routeTimings.joinToString(" ")) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
