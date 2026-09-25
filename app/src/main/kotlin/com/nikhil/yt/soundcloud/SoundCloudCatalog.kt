package com.nikhil.yt.soundcloud

import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe

internal object SoundCloudCatalog {
    private const val SEARCH_CACHE_TTL_MS = 90_000L

    @Volatile
    private var cachedSearch: CachedSearch? = null

    private data class CachedSearch(
        val query: String,
        val loadedAtMs: Long,
        val page: SearchPage,
    )

    data class Track(
        val title: String,
        val artist: String,
        val uploaderUrl: String?,
        val artworkUrl: String?,
        val permalink: String,
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

    data class SearchPage(
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

    data class PlaylistContinuation(
        val cursor: SoundCloudNewPipe.PlaylistCursor,
    )

    data class PlaylistChunk(
        val tracks: List<Track>,
        val continuation: PlaylistContinuation?,
    )

    data class PlaylistDetails(
        val url: String,
        val title: String,
        val uploader: String,
        val uploaderUrl: String?,
        val artworkUrl: String?,
        val trackCount: Long,
        val tracks: List<Track>,
        val continuation: PlaylistContinuation?,
    )

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data object RateLimited : Result<Nothing>
        data object Unavailable : Result<Nothing>
    }

    fun search(query: String): Result<SearchPage> {
        val normalized = query.trim()
        val now = System.currentTimeMillis()
        cachedSearch
            ?.takeIf { it.query == normalized && now - it.loadedAtMs <= SEARCH_CACHE_TTL_MS }
            ?.let { return Result.Success(it.page) }

        val loaded = capture {
            val r = SoundCloudNewPipe.searchAll(normalized)
            SearchPage(
                tracks = r.tracks.map(::track),
                users = r.users.map { User(it.url, it.name, it.avatarUrl, it.followerCount, it.verified) },
                playlists = r.playlists.map(::playlist),
            )
        }
        if (loaded is Result.Success) {
            cachedSearch = CachedSearch(
                query = normalized,
                loadedAtMs = System.currentTimeMillis(),
                page = loaded.value,
            )
        }
        return loaded
    }

    fun profile(url: String): Result<Profile> = capture {
        val p = SoundCloudNewPipe.profile(url)
        Profile(
            url = p.url,
            name = p.name,
            avatarUrl = p.avatarUrl,
            bannerUrl = p.bannerUrl,
            description = p.description,
            followerCount = p.followerCount,
            verified = p.verified,
            tracks = p.tracks.map(::track),
            playlists = p.playlists.map(::playlist),
        )
    }

    fun playlist(url: String): Result<PlaylistDetails> = capture {
        val p = SoundCloudNewPipe.playlist(url)
        PlaylistDetails(
            url = p.url,
            title = p.title,
            uploader = p.uploader,
            uploaderUrl = p.uploaderUrl,
            artworkUrl = p.artworkUrl,
            trackCount = p.trackCount,
            tracks = p.tracks.map(::track),
            continuation = p.cursor?.let(::PlaylistContinuation),
        )
    }

    fun playlistMore(
        url: String,
        continuation: PlaylistContinuation,
    ): Result<PlaylistChunk> = capture {
        val p = SoundCloudNewPipe.playlistMore(url, continuation.cursor)
        PlaylistChunk(
            tracks = p.tracks.map(::track),
            continuation = p.cursor?.let(::PlaylistContinuation),
        )
    }

    private fun track(t: SoundCloudNewPipe.Track) = Track(
        title = t.title,
        artist = t.artist,
        uploaderUrl = t.uploaderUrl,
        artworkUrl = t.artworkUrl,
        permalink = t.url,
        durationSeconds = t.durationSeconds,
    )

    private fun playlist(p: SoundCloudNewPipe.Playlist) = Playlist(
        url = p.url,
        title = p.title,
        uploader = p.uploader,
        uploaderUrl = p.uploaderUrl,
        artworkUrl = p.artworkUrl,
        trackCount = p.trackCount,
    )

    private inline fun <T> capture(block: () -> T): Result<T> = try {
        Result.Success(block())
    } catch (exception: Exception) {
        val message = generateSequence(exception as Throwable?) { it.cause }
            .take(8)
            .mapNotNull { it?.message }
            .joinToString(" ")
        if ("429" in message || "rate limit" in message.lowercase() || "recaptcha" in message.lowercase()) {
            Result.RateLimited
        } else Result.Unavailable
    }
}
