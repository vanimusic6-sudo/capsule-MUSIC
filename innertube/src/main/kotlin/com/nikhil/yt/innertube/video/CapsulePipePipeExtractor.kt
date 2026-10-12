/* Capsule MUSIC — isolated maintained PipePipe fallback. GPL-3.0. */
package com.nikhil.yt.innertube.video

import dev.maxrave.pipepipe.extractor.NewPipe
import dev.maxrave.pipepipe.extractor.stream.Stream
import dev.maxrave.pipepipe.extractor.stream.DeliveryMethod
import dev.maxrave.pipepipe.extractor.stream.VideoStream

/** Relocated package keeps the existing MUSIC/SoundCloud NewPipe instance untouched. */
internal object CapsulePipePipeExtractor {
    private var initialized = false

    @Synchronized
    fun resolve(id: String, quality: CapsuleNewPipeQuality, muxedOnly: Boolean): CapsuleNewPipeResolved? {
        if (!initialized) { NewPipe.init(CapsulePipePipeDownloader()); initialized = true }
        val url = "https://www.youtube.com/watch?v=$id"
        val extractor = NewPipe.getServiceByUrl(url).getStreamExtractor(url)
        extractor.fetchPage()
        val now = System.currentTimeMillis()
        fun progressive(s: Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            s.content.startsWith("https://") && signedVideoExpiryMs(s.content, now) > now + 45_000L
        val videos = extractor.videoOnlyStreams.filter(::progressive)
        val audios = extractor.audioStreams.filter(::progressive)
        if (!muxedOnly) {
            val vt = videos.map { v -> CapsuleDashTrack(v.content, v.itag, v.format?.mimeType.orEmpty(), v.codec.orEmpty(),
                v.bitrate, v.initStart.toLong(), v.initEnd.toLong(), v.indexStart.toLong(), v.indexEnd.toLong(), v.width, v.height, v.fps) }
            val at = audios.map { a -> CapsuleDashTrack(a.content, a.itag, a.format?.mimeType.orEmpty(), a.codec.orEmpty(),
                a.bitrate, a.initStart.toLong(), a.initEnd.toLong(), a.indexStart.toLong(), a.indexEnd.toLong()) }
            indexedDashVideo(id, extractor.length * 1000L, vt, at, quality,
                (vt + at).minOfOrNull { signedVideoExpiryMs(it.url, now) } ?: now,
                userAgent = CapsulePipePipeDownloader.USER_AGENT)?.let { return it }
            selectDirectHlsManifest(extractor.hlsUrl, id, quality, now)?.let { return it }
        }
        fun choose(streams: List<VideoStream>) = streams.filter { it.height in 1..quality.maxHeight }
            .maxWithOrNull(compareBy<VideoStream> { it.height }.thenBy { it.fps }.thenBy { it.bitrate })
        val muxed = choose(extractor.videoStreams.filter(::progressive))
        val audio = audios.maxByOrNull { it.bitrate }
        val adaptive = if (!muxedOnly && audio != null) choose(videos) else null
        val selected = if (adaptive != null && adaptive.height > (muxed?.height ?: 0)) adaptive else muxed ?: adaptive ?: return null
        val sound = if (selected === muxed) null else audio ?: return null
        return CapsuleNewPipeResolved(id, selected.content, sound?.content, "${selected.height}p", selected.width,
            selected.height, selected.itag, sound?.itag ?: -1,
            listOfNotNull(selected.content, sound?.content).minOf { signedVideoExpiryMs(it, now) },
            userAgent = CapsulePipePipeDownloader.USER_AGENT)
    }
}
