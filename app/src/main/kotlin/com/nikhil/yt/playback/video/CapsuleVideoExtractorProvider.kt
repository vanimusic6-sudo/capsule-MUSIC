/* Capsule MUSIC - isolated NewPipe IPC provider. GPL-3.0 */
package com.nikhil.yt.playback.video

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.nikhil.yt.innertube.video.CapsuleNewPipeExtractor
import com.nikhil.yt.innertube.video.CapsuleNewPipeFailure
import com.nikhil.yt.innertube.video.CapsuleNewPipeQuality

class CapsuleVideoExtractorProvider : ContentProvider() {
    override fun onCreate(): Boolean { CapsuleNewPipeExtractor.initializeIfNeeded(); return true }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != CapsuleVideoIpc.METHOD_RESOLVE) return CapsuleVideoIpc.failureBundle(CapsuleVideoFailure.UNKNOWN, "Unsupported VIDEO extractor method")
        val videoId = extras?.getString(CapsuleVideoIpc.EXTRA_VIDEO_ID)?.trim().orEmpty()
        val quality = CapsuleVideoIpc.qualityFromWire(extras?.getString(CapsuleVideoIpc.EXTRA_QUALITY))
        val muxedOnly = extras?.getBoolean(CapsuleVideoIpc.EXTRA_MUXED_ONLY, false) ?: false
        if (videoId.isBlank()) return CapsuleVideoIpc.failureBundle(CapsuleVideoFailure.UNAVAILABLE, "Missing YouTube video id")

        return runCatching {
            CapsuleNewPipeExtractor.resolve(videoId, CapsuleNewPipeQuality.valueOf(quality.name), muxedOnly)
        }.fold(
            onSuccess = { r -> CapsuleVideoIpc.successBundle(CapsuleResolvedVideo(r.videoId, r.videoUrl, r.audioUrl, r.qualityLabel, r.width, r.height, r.videoItag, r.audioItag, r.expiresAtMs)) },
            onFailure = { t ->
                val failure = when (CapsuleNewPipeExtractor.classify(t)) {
                    CapsuleNewPipeFailure.UNAVAILABLE -> CapsuleVideoFailure.UNAVAILABLE
                    CapsuleNewPipeFailure.NETWORK -> CapsuleVideoFailure.NETWORK
                    CapsuleNewPipeFailure.EXTRACTOR -> CapsuleVideoFailure.EXTRACTOR
                    CapsuleNewPipeFailure.RATE_LIMITED -> CapsuleVideoFailure.RATE_LIMITED
                    CapsuleNewPipeFailure.BOT_BLOCKED -> CapsuleVideoFailure.BOT_BLOCKED
                    CapsuleNewPipeFailure.UNKNOWN -> CapsuleVideoFailure.UNKNOWN
                }
                CapsuleVideoIpc.failureBundle(failure, t.message ?: t::class.java.simpleName)
            },
        )
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
