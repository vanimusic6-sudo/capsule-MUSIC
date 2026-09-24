package com.nikhil.yt.innertube.soundcloud

import com.nikhil.yt.innertube.pages.NewPipeUtils
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URI

/**
 * SoundCloud adapter backed by the already bundled NewPipeExtractor.
 * Never sends Capsule's YouTube account credentials or a user-provided OAuth token.
 * Calls are blocking and must run on Dispatchers.IO.
 */
object SoundCloudNewPipe {
    data class Track(
        val url: String,
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val durationSeconds: Long,
    )

    data class Stream(
        val url: String,
        val isHls: Boolean,
    )

    fun search(query: String): List<Track> {
        val q = query.trim().take(180)
        if (q.isBlank()) return emptyList()
        NewPipeUtils.prepareSoundCloud()
        val service = NewPipe.getService("SoundCloud")
        val handler = service.searchQHFactory.fromQuery(
            q, listOf(SoundcloudSearchQueryHandlerFactory.TRACKS), ""
        )
        return SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val url = item.url.takeIf(::isSoundCloudTrackUrl) ?: return@mapNotNull null
                val title = item.name.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Track(
                    url = url,
                    title = title,
                    artist = item.uploaderName.orEmpty().trim().ifBlank { "SoundCloud" },
                    artworkUrl = item.thumbnails.firstOrNull()?.url?.let(::soundCloudArtworkAtFullSize),
                    durationSeconds = item.duration,
                )
            }
            .distinctBy { it.url }
            .take(20)
    }

    /**
     * SoundCloud search thumbnails are typically -large (100px). Ask its image
     * CDN for the existing 500px artwork rather than stretching a 100px bitmap.
     * Keep unknown/external URLs unchanged; not every upload has a 500px original.
     */
    internal fun soundCloudArtworkAtFullSize(url: String): String? {
        if (!url.startsWith("https://")) return null
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host !in setOf("i1.sndcdn.com", "i2.sndcdn.com", "i3.sndcdn.com", "i4.sndcdn.com")) {
            return url
        }
        return url.replace(Regex("-large(?=\\.(?:jpg|jpeg|png|webp)(?:\\?|$))", RegexOption.IGNORE_CASE), "-t500x500")
    }

    /** Resolves the selected SoundCloud track, never a YouTube media ID. */
    fun resolve(trackUrl: String): Stream {
        require(isSoundCloudTrackUrl(trackUrl)) { "Not a SoundCloud track URL" }
        NewPipeUtils.prepareSoundCloud()
        val info = StreamInfo.getInfo(NewPipe.getService("SoundCloud"), trackUrl)
        val options = info.audioStreams
            .asSequence()
            .filter { it.isUrl && it.content.startsWith("https://") }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.HLS }
            .toList()
        val selected = options.maxWithOrNull(
            compareBy<org.schabi.newpipe.extractor.stream.AudioStream> {
                if (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) 1 else 0
            }.thenBy { it.averageBitrate }
        )
        if (selected != null) {
            return Stream(
                url = selected.content,
                isHls = selected.deliveryMethod == DeliveryMethod.HLS,
            )
        }
        val fallback = info.hlsUrl
        if (fallback.startsWith("https://")) return Stream(fallback, isHls = true)
        throw IllegalStateException("NewPipe returned no compatible SoundCloud audio stream")
    }

    internal fun isSoundCloudTrackUrl(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase() in setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com") &&
                segments.size >= 2 &&
                segments[0].lowercase() !in setOf("discover", "search", "stream", "you") &&
                segments[1].lowercase() !in setOf("sets", "likes", "reposts", "albums")
        }.getOrDefault(false)
}
