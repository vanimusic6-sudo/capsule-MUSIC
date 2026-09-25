package com.nikhil.yt.soundcloud

import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal object SoundCloudCatalog {
    private const val SEARCH_CACHE_TTL_MS = 90_000L
    private const val TRACK_ACCESS_CACHE_TTL_MS = 10 * 60_000L
    private const val TRACK_ACCESS_CONCURRENCY = 4

    private data class CachedTrackAccess(
        val loadedAtMs: Long,
        val access: SoundCloudNewPipe.TrackAccess,
    )

    private val trackAccessCache =
        ConcurrentHashMap<String, CachedTrackAccess>()

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
        /**
         * null = not inspected yet, true = NewPipe exposes progressive HTTP,
         * false = playback is possible but only via a non-downloadable delivery.
         */
        val downloadable: Boolean? = null,
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

    data class SearchContinuation(val cursor: SoundCloudNewPipe.SearchCursor)

    data class SearchPage(
        val tracks: List<Track>,
        val users: List<User>,
        val playlists: List<Playlist>,
        val continuation: SearchContinuation?,
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
                continuation = r.cursor?.let(::SearchContinuation),
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

    fun searchMore(continuation: SearchContinuation): Result<SearchPage> = capture {
        val r = SoundCloudNewPipe.searchMore(continuation.cursor)
        SearchPage(
            tracks = r.tracks.map(::track),
            users = r.users.map { User(it.url, it.name, it.avatarUrl, it.followerCount, it.verified) },
            playlists = r.playlists.map(::playlist),
            continuation = r.cursor?.let(::SearchContinuation),
        )
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

    /**
     * Remove tracks which NewPipe can positively identify as unplayable after
     * encrypted SoundCloud transcodings have been filtered out. Network/parser
     * failures are treated as unknown and kept so a temporary outage does not
     * erase the user's results.
     *
     * The checks are bounded to four concurrent extractors and cached as flags
     * only. Signed media URLs are never cached here.
     */
    suspend fun validateTracks(
        tracks: List<Track>,
    ): List<Track> = coroutineScope {
        if (tracks.isEmpty()) return@coroutineScope emptyList()

        val gate = Semaphore(TRACK_ACCESS_CONCURRENCY)
        tracks
            .distinctBy { it.permalink }
            .map { track ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val now = System.currentTimeMillis()
                        val cached = trackAccessCache[track.permalink]
                            ?.takeIf {
                                now - it.loadedAtMs <= TRACK_ACCESS_CACHE_TTL_MS
                            }
                            ?.access

                        val access = cached ?: runCatching {
                            runInterruptible {
                                SoundCloudNewPipe.inspectTrack(track.permalink)
                            }
                        }.getOrNull()?.also {
                            trackAccessCache[track.permalink] =
                                CachedTrackAccess(
                                    loadedAtMs = System.currentTimeMillis(),
                                    access = it,
                                )
                        }

                        when {
                            access == null -> track
                            !access.playable -> null
                            else -> track.copy(
                                downloadable = access.downloadable,
                            )
                        }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private fun track(
        t: SoundCloudNewPipe.Track,
    ) = Track(
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
