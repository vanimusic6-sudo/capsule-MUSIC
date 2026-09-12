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
import com.nikhil.yt.innertube.video.CapsuleNewPipeFailure
import com.nikhil.yt.innertube.video.CapsuleNewPipeQuality
import com.nikhil.yt.innertube.video.VideoExtractionRequests

class CapsuleVideoExtractorProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        CapsuleNewPipeExtractor.initializeIfNeeded()
        return true
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        val requestId = extras?.getString(CapsuleVideoIpc.EXTRA_REQUEST_ID).orEmpty()
        if (!requestId.matches(Regex("[a-fA-F0-9-]{36}"))) {
            return CapsuleVideoIpc.failureBundle(CapsuleVideoFailure.UNKNOWN, "Invalid VIDEO request id")
        }
        if (method == CapsuleVideoIpc.METHOD_CANCEL) {
            VideoExtractionRequests.cancel(requestId)
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

        if (videoId.isBlank()) {
            return CapsuleVideoIpc.failureBundle(
                CapsuleVideoFailure.UNAVAILABLE,
                "Missing YouTube video id",
            )
        }

        return runCatching {
            VideoExtractionRequests.withRequest(requestId, CapsuleVideoIpc.REQUEST_TIMEOUT_MS) {
                CapsuleNewPipeExtractor.resolve(
                    videoId,
                    CapsuleNewPipeQuality.valueOf(quality.name),
                    muxedOnly,
                )
            }
        }.fold(
            onSuccess = { resolved ->
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
                    throwable.message ?: throwable::class.java.simpleName,
                )
            },
        )
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
