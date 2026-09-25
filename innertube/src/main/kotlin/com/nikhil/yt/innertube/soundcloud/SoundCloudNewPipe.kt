package com.nikhil.yt.innertube.soundcloud

import com.nikhil.yt.innertube.pages.NewPipeUtils
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
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
 * Browse/search stays metadata-only for speed. Actual stream availability is
 * checked when a track is about to play; NewPipe itself rejects unsupported or
 * encrypted transcodings during stream extraction.
 */
object SoundCloudNewPipe {
    data class Stream(val url: String, val isHls: Boolean)

    /**
     * NewPipe's SoundCloud extractor removes encrypted transcodings before
     * exposing AudioStream objects. A successful inspection with no playable
     * streams therefore means the item is not usable by Capsule (DRM/protected,
     * unavailable or non-streamable).
     *
     * Downloads intentionally mirror NewPipe: only progressive HTTP audio is
     * downloadable. HLS remains valid for playback but is not offered offline.
     */
    data class TrackAccess(
        val playable: Boolean,
        val downloadable: Boolean,
    )

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

    class SearchCursor internal constructor(
        internal val query: String,
        internal val page: Page,
    )

    data class SearchResults(
        val tracks: List<Track>,
        val users: List<User>,
        val playlists: List<Playlist>,
        val cursor: SearchCursor?,
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

    /**
     * Opaque NewPipe continuation. The app can keep and pass it back without
     * depending on extractor internals or serializing SoundCloud page state.
     */
    class PlaylistCursor internal constructor(internal val page: Page)

    data class PlaylistPage(
        val tracks: List<Track>,
        val cursor: PlaylistCursor?,
    )

    data class PlaylistDetails(
        val url: String,
        val title: String,
        val uploader: String,
        val uploaderUrl: String?,
        val artworkUrl: String?,
        val trackCount: Long,
        val tracks: List<Track>,
        val cursor: PlaylistCursor?,
    )

    /** Backwards-compatible track-only search used by older callers/tests. */
    fun search(query: String): List<Track> = searchAll(query).tracks

    fun searchAll(query: String): SearchResults {
        val q = query.trim().take(180)
        if (q.isBlank()) return SearchResults(emptyList(), emptyList(), emptyList(), null)
        NewPipeUtils.prepareSoundCloud()
        val service = NewPipe.getService("SoundCloud")
        val handler = service.searchQHFactory.fromQuery(
            q, listOf(SoundcloudSearchQueryHandlerFactory.ALL), ""
        )
        val info = SearchInfo.getInfo(service, handler)
        return searchResults(q, info.relatedItems, info.nextPage)
    }

    fun searchMore(cursor: SearchCursor): SearchResults {
        NewPipeUtils.prepareSoundCloud()
        val service = NewPipe.getService("SoundCloud")
        val handler = service.searchQHFactory.fromQuery(
            cursor.query,
            listOf(SoundcloudSearchQueryHandlerFactory.ALL),
            "",
        )
        val page = SearchInfo.getMoreItems(service, handler, cursor.page)
        return searchResults(cursor.query, page.items, page.nextPage)
    }

    private fun searchResults(
        query: String,
        items: List<*>,
        nextPage: Page?,
    ): SearchResults = SearchResults(
        tracks = items.filterIsInstance<StreamInfoItem>()
            .mapNotNull(::toPlayableTrack)
            .distinctBy { it.url },
        users = items.filterIsInstance<ChannelInfoItem>()
            .mapNotNull(::toUser)
            .distinctBy { it.url },
        playlists = items.filterIsInstance<PlaylistInfoItem>()
            .mapNotNull(::toPlaylist)
            .distinctBy { it.url },
        cursor = nextPage?.takeIf { Page.isValid(it) }?.let { SearchCursor(query, it) },
    )

    fun profile(url: String): Profile {
        NewPipeUtils.prepareSoundCloud()
        val service = NewPipe.getService("SoundCloud")
        val info = ChannelInfo.getInfo(service, url)
        val tracksTab = info.tabs.firstOrNull { it.contentFilters.firstOrNull() == ChannelTabs.TRACKS }
        val playlistsTab = info.tabs.firstOrNull { it.contentFilters.firstOrNull() == ChannelTabs.PLAYLISTS }
        // One broken tab should not make the whole SoundCloud account page disappear.
        val tracks = tracksTab?.let { tab ->
            runCatching { ChannelTabInfo.getInfo(service, tab).relatedItems }.getOrNull()
        }
            .orEmpty()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull(::toPlayableTrack)
            .distinctBy { it.url }
        val playlists = playlistsTab?.let { tab ->
            runCatching { ChannelTabInfo.getInfo(service, tab).relatedItems }.getOrNull()
        }
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

    /**
     * Returns only the initial playlist page.
     *
     * Loading every continuation before returning made opening a playlist wait
     * for up to eight sequential network requests. Call [playlistMore] after
     * painting this first page so the UI becomes usable immediately.
     */
    fun playlist(url: String): PlaylistDetails {
        NewPipeUtils.prepareSoundCloud()
        val service = NewPipe.getService("SoundCloud")
        val info = PlaylistInfo.getInfo(service, url)
        val tracks = info.relatedItems
            .asSequence()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull(::toPlayableTrack)
            .distinctBy { it.url }
            .toList()
        return PlaylistDetails(
            url = info.url,
            title = info.name,
            uploader = info.uploaderName.orEmpty().ifBlank { "SoundCloud" },
            uploaderUrl = info.uploaderUrl?.takeIf(::isSoundCloudUserUrl),
            artworkUrl = bestSoundCloudArtwork(info.thumbnails),
            trackCount = info.streamCount,
            tracks = tracks,
            cursor = info.nextPage
                ?.takeIf { Page.isValid(it) }
                ?.let(::PlaylistCursor),
        )
    }

    fun playlistMore(
        url: String,
        cursor: PlaylistCursor,
    ): PlaylistPage {
        NewPipeUtils.prepareSoundCloud()
        val service = NewPipe.getService("SoundCloud")
        val page = PlaylistInfo.getMoreItems(service, url, cursor.page)
        return PlaylistPage(
            tracks = page.items
                .asSequence()
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull(::toPlayableTrack)
                .distinctBy { it.url }
                .toList(),
            cursor = page.nextPage
                ?.takeIf { Page.isValid(it) }
                ?.let(::PlaylistCursor),
        )
    }

    /**
     * Resolves only playable, non-encrypted SoundCloud audio.
     * In NewPipe v0.26.5 encrypted protocols are excluded by the SoundCloud
     * extractor before AudioStream objects are returned.
     */
    fun inspectTrack(trackUrl: String): TrackAccess {
        val info =
            try {
                streamInfo(trackUrl)
            } catch (_: SoundCloudGoPlusContentException) {
                // SoundCloud policy=SNIP: Go+ protected preview/content.
                return TrackAccess(
                    playable = false,
                    downloadable = false,
                )
            } catch (_: GeographicRestrictionException) {
                return TrackAccess(
                    playable = false,
                    downloadable = false,
                )
            } catch (_: ContentNotAvailableException) {
                return TrackAccess(
                    playable = false,
                    downloadable = false,
                )
            }

        val streams = playableAudioStreams(info)
        val fallbackHls = info.hlsUrl.takeIf { it.startsWith("https://") }
        return TrackAccess(
            playable = streams.isNotEmpty() || fallbackHls != null,
            downloadable = streams.any {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
            },
        )
    }

    /**
     * Public boundary for the app module. DownloadManager persists requests
     * across app upgrades, so old builds may leave a signed sndcdn.com URL
     * where the new downloader expects a canonical SoundCloud permalink.
     */
    fun isTrackUrl(url: String): Boolean = isSoundCloudTrackUrl(url)

    fun resolve(trackUrl: String): Stream =
        resolveInternal(trackUrl, progressiveOnly = false)

    fun resolveProgressive(trackUrl: String): Stream =
        resolveInternal(trackUrl, progressiveOnly = true)

    private fun resolveInternal(
        trackUrl: String,
        progressiveOnly: Boolean,
    ): Stream {
        val info = streamInfo(trackUrl)
        val options = playableAudioStreams(info)
            .asSequence()
            .filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP ||
                    (!progressiveOnly && it.deliveryMethod == DeliveryMethod.HLS)
            }
            .toList()

        val selected = options.maxWithOrNull(
            compareBy<org.schabi.newpipe.extractor.stream.AudioStream> {
                if (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) 1 else 0
            }.thenBy { it.averageBitrate },
        )

        if (selected != null) {
            return Stream(
                url = selected.content,
                isHls = selected.deliveryMethod == DeliveryMethod.HLS,
            )
        }

        if (!progressiveOnly) {
            val fallback = info.hlsUrl
            if (fallback.startsWith("https://")) {
                return Stream(fallback, true)
            }
        }

        throw IllegalStateException(
            if (progressiveOnly) {
                "NewPipe returned no progressive SoundCloud audio stream"
            } else {
                "NewPipe returned no non-DRM SoundCloud audio stream"
            },
        )
    }

    private fun streamInfo(
        trackUrl: String,
    ): StreamInfo {
        require(isSoundCloudTrackUrl(trackUrl)) { "Not a SoundCloud track URL" }
        NewPipeUtils.prepareSoundCloud()
        return StreamInfo.getInfo(
            NewPipe.getService("SoundCloud"),
            trackUrl,
        )
    }

    private fun playableAudioStreams(
        info: StreamInfo,
    ) = info.audioStreams
        .asSequence()
        .filter { stream ->
            stream.isUrl &&
                stream.content.startsWith("https://") &&
                (
                    stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP ||
                        stream.deliveryMethod == DeliveryMethod.HLS
                )
        }
        .toList()

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
            uploaderUrl = item.uploaderUrl?.takeIf(::isSoundCloudUserUrl) ?: soundCloudUserUrlFromTrack(url),
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

    /**
     * Search/playlist items occasionally omit uploaderUrl even though the
     * canonical track permalink already contains the account slug.
     */
    internal fun soundCloudUserUrlFromTrack(url: String): String? {
        if (!isSoundCloudTrackUrl(url)) return null
        return runCatching {
            val user = URI(url).path.orEmpty()
                .split('/')
                .firstOrNull { it.isNotBlank() }
                ?: return@runCatching null
            "https://soundcloud.com/$user"
        }.getOrNull()
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
