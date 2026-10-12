/* Capsule MUSIC — indexed on-demand media, GPL-3.0. */
package com.nikhil.yt.innertube.video

import java.net.URI

internal data class CapsuleDashTrack(
    val url: String, val itag: Int, val mimeType: String, val codecs: String,
    val bitrate: Int, val initStart: Long, val initEnd: Long,
    val indexStart: Long, val indexEnd: Long,
    val width: Int = 0, val height: Int = 0, val fps: Int = 0,
) {
    fun valid(): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host?.endsWith(".googlevideo.com") == true && uri.userInfo == null &&
            itag > 0 && mimeType in setOf("video/mp4", "video/webm", "audio/mp4", "audio/webm") &&
            codecs.matches(Regex("[a-zA-Z0-9.,_-]+")) && initStart >= 0 && initEnd >= initStart &&
            indexStart > initEnd && indexEnd >= indexStart &&
            initEnd - initStart < 128 * 1024 && indexEnd - indexStart < 128 * 1024
    }
}

/** Sideload the small manifest; Media3 reads the real initialization and SIDX/Cues ranges. */
internal fun indexedDashVideo(
    videoId: String, durationMs: Long, videos: List<CapsuleDashTrack>, audios: List<CapsuleDashTrack>,
    quality: CapsuleNewPipeQuality, expiresAtMs: Long, userAgent: String? = null,
): CapsuleNewPipeResolved? {
    if (durationMs <= 0L) return null
    val eligible = videos.filter { it.valid() && it.width > 0 && it.height in 1..quality.maxHeight && it.mimeType.startsWith("video/") }
    val height = eligible.maxOfOrNull { it.height } ?: return null
    // Keep the lower ladder for hardware compatibility. The VIDEO selector
    // chooses the highest supported bitrate, without bandwidth downgrades.
    val selected = eligible.sortedWith(compareByDescending<CapsuleDashTrack> { it.height }
        .thenByDescending { it.fps }.thenByDescending { it.bitrate })
    val audio = audios.filter { it.valid() && it.mimeType.startsWith("audio/") }.maxByOrNull { it.bitrate } ?: return null
    val video = selected.first()
    val xml = buildString {
        append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" minBufferTime=\"PT1S\" mediaPresentationDuration=\"PT${durationMs / 1000.0}S\"><Period>")
        fun tracks(id: Int, type: String, items: List<CapsuleDashTrack>) {
            append("<AdaptationSet id=\"$id\" contentType=\"$type\">")
            for (track in items) {
                append("<Representation id=\"${track.itag}\" mimeType=\"${track.mimeType}\" codecs=\"${track.codecs}\" bandwidth=\"${track.bitrate.coerceAtLeast(1)}\"")
                if (type == "video") append(" width=\"${track.width}\" height=\"${track.height}\" frameRate=\"${track.fps.coerceAtLeast(1)}\"")
                append("><BaseURL>${escapeDashXml(track.url)}</BaseURL><SegmentBase indexRange=\"${track.indexStart}-${track.indexEnd}\" indexRangeExact=\"true\"><Initialization range=\"${track.initStart}-${track.initEnd}\"/></SegmentBase></Representation>")
            }
            append("</AdaptationSet>")
        }
        tracks(1, "video", selected)
        tracks(2, "audio", listOf(audio))
        append("</Period></MPD>")
    }
    return CapsuleNewPipeResolved(videoId, video.url, audio.url, "${height}p", video.width, height,
        video.itag, audio.itag, expiresAtMs, delivery = CapsuleVideoDelivery.DASH,
        maxHeight = quality.maxHeight, dashManifest = xml, userAgent = userAgent)
}

private fun escapeDashXml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

internal fun signedVideoExpiryMs(url: String, nowMs: Long = System.currentTimeMillis()): Long {
    val seconds = runCatching { URI(url).rawQuery }.getOrNull().orEmpty().split('&')
        .firstOrNull { it.startsWith("expire=") }?.substringAfter('=')?.toLongOrNull()
    return if (seconds != null && seconds in 0..Long.MAX_VALUE / 1000L) seconds * 1000L
        else nowMs + 3_600_000L
}
