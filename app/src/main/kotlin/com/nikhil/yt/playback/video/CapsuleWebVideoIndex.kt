/* Capsule MUSIC — use the selected response's real SIDX/Cues ranges. GPL-3.0. */
package com.nikhil.yt.playback.video

import com.nikhil.yt.innertube.video.CapsuleDashTrack
import com.nikhil.yt.innertube.video.CapsuleNewPipeQuality
import com.nikhil.yt.innertube.video.indexedDashVideo
import kotlinx.serialization.json.*

/** Retain only bounded format metadata, never raw responses, cookies or alternate signed URLs. */
internal class CapsuleWebVideoIndex private constructor(
    val videoId: String,
    private val durationMs: Long,
    private val formats: Map<Int, Format>,
) {
    private data class Format(
        val itag: Int, val mime: String, val codecs: String, val bitrate: Int,
        val initStart: Long, val initEnd: Long, val indexStart: Long, val indexEnd: Long,
        val width: Int, val height: Int, val fps: Int,
    ) {
        fun track(url: String) = CapsuleDashTrack(url, itag, mime, codecs, bitrate,
            initStart, initEnd, indexStart, indexEnd, width, height, fps)
    }

    fun indexed(source: CapsuleResolvedVideo): CapsuleResolvedVideo? {
        if (source.videoId != videoId) return null
        val video = formats[source.videoItag]?.track(source.videoUrl) ?: return null
        val audio = formats[source.audioItag]?.track(source.audioUrl ?: return null) ?: return null
        val quality = CapsuleNewPipeQuality.entries.firstOrNull { it.maxHeight == source.maxHeight } ?: return null
        val indexed = indexedDashVideo(videoId, durationMs, listOf(video), listOf(audio), quality,
            source.expiresAtMs, source.userAgent) ?: return null
        return source.copy(delivery = indexed.delivery, dashManifest = indexed.dashManifest)
    }

    companion object {
        fun fromPlayerResponse(root: JsonObject): CapsuleWebVideoIndex? {
            return runCatching {
                val details = root["videoDetails"] as? JsonObject ?: return null
                val id = details.text("videoId")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return null
                val seconds = details.number("lengthSeconds")?.takeIf { it in 1L..86_400L } ?: return null
                val streaming = root["streamingData"] as? JsonObject ?: return null
                val adaptive = streaming["adaptiveFormats"] as? JsonArray ?: return null
                val formats = adaptive.take(64).mapNotNull { element ->
                    val f = element as? JsonObject ?: return@mapNotNull null
                    val init = f["initRange"] as? JsonObject ?: return@mapNotNull null
                    val index = f["indexRange"] as? JsonObject ?: return@mapNotNull null
                    val mime = f.text("mimeType") ?: return@mapNotNull null
                    val codecs = mime.substringAfter("codecs=\"", "").substringBefore('"').replace(" ", "")
                    Format(
                        f.number("itag")?.toInt() ?: return@mapNotNull null,
                        mime.substringBefore(';').trim(), codecs,
                        f.number("bitrate")?.coerceIn(1, Int.MAX_VALUE.toLong())?.toInt() ?: 1,
                        init.number("start") ?: return@mapNotNull null,
                        init.number("end") ?: return@mapNotNull null,
                        index.number("start") ?: return@mapNotNull null,
                        index.number("end") ?: return@mapNotNull null,
                        f.number("width")?.toInt() ?: 0, f.number("height")?.toInt() ?: 0,
                        f.number("fps")?.toInt() ?: 0,
                    )
                }.associateBy { it.itag }
                CapsuleWebVideoIndex(id, seconds * 1000L, formats)
            }.getOrNull()
        }

        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.number(key: String) = text(key)?.toLongOrNull()
    }
}
