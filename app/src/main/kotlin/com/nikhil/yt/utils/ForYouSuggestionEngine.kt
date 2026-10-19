/*
 * Capsule Music — local-first music discovery.
 * Licensed Under GPL-3.0
 */
package com.nikhil.yt.utils

import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.db.entities.SongSkipEntity
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.innertube.models.filterExplicit
import com.nikhil.yt.innertube.models.filterVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.math.ln
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A discovery shelf, not a second copy of the listening history.
 *
 * Seed selection uses listening time in units of complete plays (not raw
 * milliseconds), likes and skips. Each seed supplies a small, interleaved
 * batch of related tracks; skipped or already heard tracks are excluded.
 * No remote recommendation requests are made until local preferences are
 * sufficiently established.
 */
@Singleton
class ForYouSuggestionEngine @Inject constructor(
    private val database: MusicDatabase
) {
    companion object {
        const val MAX_SUGGESTIONS = 40
        private const val MAX_SEEDS = 4
        private const val CANDIDATES_PER_SEED = 15
        private const val MAX_TRACKS_PER_ARTIST = 3
        private const val MIN_DISTINCT_TRACKS = 8
        private const val MIN_LISTENED_MS = 20 * 60 * 1000L
        private const val MILLIS_PER_DAY = 86_400_000L
    }

    private fun scoreSeed(
        song: Song,
        skips: Map<String, SongSkipEntity>,
        liked: Set<String>,
        recent: Set<String>
    ): Double {
        val durationMs = song.song.duration
            .takeIf { it > 0 }?.times(1000L) ?: 180_000L
        val equivalentPlays = song.song.totalPlayTime.toDouble() / durationMs
        val skipCount = skips[song.id]?.skipCount ?: 0

        return ln(1.0 + equivalentPlays.coerceAtLeast(0.0)) * 4.0 +
            (if (song.id in liked) 3.5 else 0.0) +
            (if (song.id in recent) 0.8 else 0.0) -
            skipCount * 2.5
    }

    suspend fun getSuggestions(
        hideExplicit: Boolean = false,
        hideVideo: Boolean = false
    ): List<SongItem> {
        val now = System.currentTimeMillis()
        val allSongs = database.mostPlayedSongs(now - 30 * MILLIS_PER_DAY, limit = 100)
            .first().distinctBy { it.id }
        val likedSongs = database.likedSongsByPlayTimeAsc().first()
        val recentEvents = database.events().first().take(20)
        val skips = database.getAllSkips().first().associateBy { it.songId }

        // Sparse history cannot reliably identify a person's tastes.
        val heard = allSongs.filter { it.song.totalPlayTime >= 30_000L }
        if (heard.size < MIN_DISTINCT_TRACKS ||
            heard.sumOf { it.song.totalPlayTime.coerceAtLeast(0L) } < MIN_LISTENED_MS
        ) return emptyList()

        val likedIds = likedSongs.mapTo(hashSetOf()) { it.id }
        val recentIds = recentEvents.mapNotNullTo(hashSetOf()) { it.song?.id }
        val seeds = allSongs
            .asSequence()
            .filter { it.song.totalPlayTime >= 30_000L || it.id in likedIds }
            .filter { (skips[it.id]?.skipCount ?: 0) < 3 || it.id in likedIds }
            .sortedByDescending { scoreSeed(it, skips, likedIds, recentIds) }
            // Prevent one heavily replayed artist from monopolizing discovery.
            .distinctBy { it.artists.firstOrNull()?.id ?: it.id }
            .take(MAX_SEEDS)
            .toList()

        if (seeds.isEmpty()) return emptyList()

        val groups = mutableListOf<List<SongItem>>()
        for (seed in seeds) {
            try {
                val endpoint = YouTube.next(WatchEndpoint(videoId = seed.id))
                    .getOrNull()?.relatedEndpoint ?: continue
                val page = YouTube.related(endpoint).getOrNull() ?: continue
                groups += page.songs
                    .filterExplicit(hideExplicit)
                    .filterVideo(hideVideo)
                    .filter { item ->
                        item.title.isNotBlank() &&
                            (item.duration == null || item.duration in 30..900) &&
                            // Explicit negative feedback is stronger than a related match.
                            !(skips[item.id]?.let {
                                it.skipCount >= 2 &&
                                    now - it.lastSkippedAt < 60 * MILLIS_PER_DAY
                            } ?: false)
                    }
                    .take(CANDIDATES_PER_SEED)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A failed related shelf must not block the remaining seeds.
            }
        }

        val seen = allSongs.mapTo(hashSetOf()) { it.id }
        seen.addAll(recentIds)
        val artistCounts = mutableMapOf<String, Int>()
        val result = ArrayList<SongItem>(MAX_SUGGESTIONS)

        // Interleave different seeds rather than filling one entire artist radio first.
        for (position in 0 until CANDIDATES_PER_SEED) {
            for (group in groups) {
                val song = group.getOrNull(position) ?: continue
                if (song.id in seen) continue
                val artistKey = song.artists.firstOrNull()?.name
                    ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: song.id
                val count = artistCounts[artistKey] ?: 0
                if (count >= MAX_TRACKS_PER_ARTIST) continue

                seen += song.id
                artistCounts[artistKey] = count + 1
                result += song
                if (result.size == MAX_SUGGESTIONS) return result
            }
        }
        return result
    }

    suspend fun recordSkip(songId: String) {
        val existing = database.getSkip(songId)
        if (existing != null) {
            database.upsertSkip(
                existing.copy(
                    skipCount = existing.skipCount + 1,
                    lastSkippedAt = System.currentTimeMillis()
                )
            )
        } else {
            database.upsertSkip(SongSkipEntity(songId = songId, skipCount = 1))
        }
    }
}
