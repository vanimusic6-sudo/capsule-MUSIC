package com.nikhil.yt.innertube.soundcloud

import com.nikhil.yt.innertube.pages.NewPipeUtils
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URI

/**
 * SoundCloud adapter backed by the already bundled NewPipeExtractor.
 * Calls are blocking and must run on Dispatchers.IO.
 *
 * DRM / Go+ rule: a track is exposed to the app only if NewPipe can resolve at
 * least one non-encrypted playable audio stream. NewPipe itself rejects
 * encrypted transcodings and Go+/blocked policies; Capsule additionally probes
 * every track before it can enter search/profile/playlist UI.
 */
object SoundCloudNewPipe {
    data class Stream(val url: String, val isHls: Boolean)

    data class Track(
        val url: String,
        val title: String,
        val artist: String,
        val uploaderUrl: String?,
        val artworkUrl: String?,
        val durationSeconds: Long,
    )

    data class User(
        val url: String,
        val name: String,
        val avatarUrl: String?,
        val followerCount: Long,
        val verified: Boolean,
    )

    data class Playlist(
        val url: String,
        val title: String,
        val uploader: String,
        val uploaderUrl: String?,
        val artworkUrl: String?,
        val trackCount: Long,
    )

    data class SearchResults(
        val tracks: List<Track>,
        val users: List<User>,
        val playlists: List<Playlist>,
    )

    data class Profile(
        val url: String,
        val name: String,
        val avatarUrl: String?,
        val bannerUrl: String?,
        val description: String,
        val followerCount: Long,
        val verified: Boolean,
        val tracks: List<Track>,
        val playlists: List<Playlist>,
    )

    data class PlaylistDetails(
        val url: String,
        val title: String,
        val uploader: String,
        val uploaderUrl: String?,
        val artworkUrl: String?,
        val trackCount: Long,
        val tracks: List<Track>,
    )

    /** Backwards-compatible track-only search used by older callers/tests. */
    fun search(query: String): List<Track> = searchAll(query).tracks

    fun searchAll(query: String): SearchResults {
        val q = query.trim().take(180)
        if (q.isBlank()) return SearchResults(emptyList(), emptyList(), emptyList())
        NewPipeUtils.prepareSoundCloud()
        val service = NewPipe.getService("SoundCloud")
        val handler = service.searchQHFactory.fromQuery(
            q, listOf(SoundcloudSearchQueryHandlerFactory.ALL), ""
        )
        val items = SearchInfo.getInfo(service, handler).relatedItems
        return SearchResults(
            tracks = items.filterIsInstance<StreamInfoItem>()
                .mapNotNull(::toPlayableTrack)
                .distinctBy { it.url }
                .take(12),
            users = items.filterIsInstance<ChannelInfoItem>()
                .mapNotNull(::toUser)
                .distinctBy { it.url }
                .take(8),
            playlists = items.filterIsInstance<PlaylistInfoItem>()
                .mapNotNull(::toPlaylist)
                .distinctBy { it.url }
                .take(8),
        )
    }

    fun profile(url: String): Profile {
        NewPipeUtils.prepareSoundCloud()
        val service = NewPipe.getService("SoundCloud")
        val info = ChannelInfo.getInfo(service, url)
        val tracksTab = info.tabs.firstOrNull { it.contentFilters.firstOrNull() == ChannelTabs.TRACKS }
        val playlistsTab = info.tabs.firstOrNull { it.contentFilters.firstOrNull() == ChannelTabs.PLAYLISTS }
        val tracks = tracksTab?.let { ChannelTabInfo.getInfo(service, it).relatedItems }
            .orEmpty()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull(::toPlayableTrack)
            .distinctBy { it.url }
        val playlists = playlistsTab?.let { ChannelTabInfo.getInfo(service, it).relatedItems }
            .orEmpty()
            .filterIsInstance<PlaylistInfoItem>()
            .mapNotNull(::toPlaylist)
            .distinctBy { it.url }
        return Profile(
            url = info.url,
            name = info.name,
            avatarUrl = bestSoundCloudArtwork(info.avatars),
            bannerUrl = bestSoundCloudArtwork(info.banners),
            description = info.description.orEmpty(),
            followerCount = info.subscriberCount,
            verified = info.isVerified,
            tracks = tracks,
            playlists = playlists,
        )
    }

    fun playlist(url: String, maxTracks: Int = 100): PlaylistDetails {
        NewPipeUtils.prepareSoundCloud()
        val service = NewPipe.getService("SoundCloud")
        val info = PlaylistInfo.getInfo(service, url)
        val candidates = ArrayList<StreamInfoItem>()
        candidates += info.relatedItems
        var next = info.nextPage
        while (next != null && candidates.size < maxTracks) {
            val page = PlaylistInfo.getMoreItems(service, url, next)
            candidates += page.items
            next = page.nextPage
        }
        val tracks = candidates.asSequence()
            .take(maxTracks)
            .mapNotNull(::toPlayableTrack)
            .distinctBy { it.url }
            .toList()
        return PlaylistDetails(
            url = info.url,
            title = info.name,
            uploader = info.uploaderName.orEmpty().ifBlank { "SoundCloud" },
            uploaderUrl = info.uploaderUrl?.takeIf { it.startsWith("https://soundcloud.com/") },
            artworkUrl = bestSoundCloudArtwork(info.thumbnails),
            trackCount = info.streamCount,
            tracks = tracks,
        )
    }

    /**
     * Resolves only playable, non-encrypted SoundCloud audio.
     * In NewPipe v0.26.5 encrypted protocols are excluded by the SoundCloud
     * extractor before AudioStream objects are returned.
     */
    fun resolve(trackUrl: String): Stream {
        require(isSoundCloudTrackUrl(trackUrl)) { "Not a SoundCloud track URL" }
        NewPipeUtils.prepareSoundCloud()
        val info = StreamInfo.getInfo(NewPipe.getService("SoundCloud"), trackUrl)
        val options = info.audioStreams
            .asSequence()
            .filter { it.isUrl && it.content.startsWith("https://") }
            .filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP ||
                    it.deliveryMethod == DeliveryMethod.HLS
            }
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
        throw IllegalStateException("NewPipe returned no non-DRM SoundCloud audio stream")
    }

    /**
     * Keep discovery metadata-only. Resolving every result here turns one search,
     * profile or playlist load into N extra StreamInfo calls and repeats the same
     * work again when playback starts.
     *
     * Actual stream availability is checked lazily by SoundCloudQueue.
     */
    private fun toPlayableTrack(item: StreamInfoItem): Track? {
        val url = item.url.takeIf(::isSoundCloudTrackUrl) ?: return null
        val title = item.name.trim().takeIf { it.isNotBlank() } ?: return null
        return Track(
            url = url,
            title = title,
            artist = item.uploaderName.orEmpty().trim().ifBlank { "SoundCloud" },
            uploaderUrl = item.uploaderUrl?.takeIf(::isSoundCloudUserUrl),
            artworkUrl = bestSoundCloudArtwork(item.thumbnails),
            durationSeconds = item.duration,
        )
    }

    private fun toUser(item: ChannelInfoItem): User? {
        val url = item.url.takeIf(::isSoundCloudUserUrl) ?: return null
        val name = item.name.trim().takeIf { it.isNotBlank() } ?: return null
        return User(
            url = url,
            name = name,
            avatarUrl = bestSoundCloudArtwork(item.thumbnails),
            followerCount = item.subscriberCount,
            verified = item.isVerified,
        )
    }

    private fun toPlaylist(item: PlaylistInfoItem): Playlist? {
        val url = item.url.takeIf(::isSoundCloudPlaylistUrl) ?: return null
        val title = item.name.trim().takeIf { it.isNotBlank() } ?: return null
        return Playlist(
            url = url,
            title = title,
            uploader = item.uploaderName.orEmpty().trim().ifBlank { "SoundCloud" },
            uploaderUrl = item.uploaderUrl?.takeIf(::isSoundCloudUserUrl),
            artworkUrl = bestSoundCloudArtwork(item.thumbnails),
            trackCount = item.streamCount,
        )
    }

    internal fun bestSoundCloudArtwork(images: List<org.schabi.newpipe.extractor.Image>): String? =
        images.asSequence()
            .filter { it.url.startsWith("https://") }
            .maxByOrNull {
                it.width.coerceAtLeast(0).toLong() * it.height.coerceAtLeast(0).toLong()
            }
            ?.url
            ?.let(::soundCloudArtworkAtFullSize)

    internal fun soundCloudArtworkAtFullSize(url: String): String? {
        if (!url.startsWith("https://")) return null
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host !in setOf("i1.sndcdn.com", "i2.sndcdn.com", "i3.sndcdn.com", "i4.sndcdn.com")) {
            return url
        }
        return url.replace(
            Regex("-large(?=\\.(?:jpg|jpeg|png|webp)(?:\\?|$))", RegexOption.IGNORE_CASE),
            "-t500x500"
        )
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

    internal fun isSoundCloudPlaylistUrl(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            val s = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
            uri.scheme.equals("https", true) &&
                uri.host?.lowercase() in setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com") &&
                s.size >= 3 && s[1].equals("sets", true)
        }.getOrDefault(false)

    internal fun isSoundCloudUserUrl(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            val s = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
            uri.scheme.equals("https", true) &&
                uri.host?.lowercase() in setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com") &&
                s.size == 1 &&
                s[0].lowercase() !in setOf("discover", "search", "stream", "you")
        }.getOrDefault(false)
}
