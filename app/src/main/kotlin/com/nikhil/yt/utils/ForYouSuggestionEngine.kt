/*
 * Velune - by Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.utils

import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.db.entities.SongSkipEntity
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.innertube.models.filterExplicit
import com.nikhil.yt.innertube.models.filterVideo
import com.nikhil.yt.innertube.models.SongItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Velune For You — Suggestion engine
 *
 * Scores songs based on:
 * - Play count (higher = better)
 * - Skip count (higher = worse)
 * - Liked status (liked = bonus)
 * - Time of day (matches listening habits)
 * - Recency (recently played gets a boost)
 */
@Singleton
class ForYouSuggestionEngine @Inject constructor(
    private val database: MusicDatabase
) {

    companion object {
        const val MAX_SUGGESTIONS = 50
        private val MORNING = 6..11
        private val AFTERNOON = 12..17
        private val EVENING = 18..21
        private val NIGHT = 22..23
    }

    /**
     * Get current time of day category
     */
    private fun getTimeOfDay(): String {
        val hour = LocalTime.now().hour
        return when (hour) {
            in MORNING -> "morning"
            in AFTERNOON -> "afternoon"
            in EVENING -> "evening"
            else -> "night"
        }
    }

    /**
     * Score a song based on play count, skips, liked status and recency
     */
    private fun scoreSong(
        song: Song,
        skipMap: Map<String, SongSkipEntity>,
        likedIds: Set<String>,
        recentIds: Set<String>,
        timeOfDay: String
    ): Float {
        return RecommendationScore.calculate(
            totalPlayTimeMs = song.song.totalPlayTime,
            durationSeconds = song.song.duration,
            skipCount = skipMap[song.id]?.skipCount ?: 0,
            liked = song.id in likedIds,
            recent = song.id in recentIds,
            timeOfDay = timeOfDay,
        )
    }

    /**
     * Build the For You suggestion list from local + YouTube related songs
     */
    suspend fun getSuggestions(
        hideExplicit: Boolean = false,
        hideVideo: Boolean = false
    ): List<SongItem> {
        val fromTimeStamp = System.currentTimeMillis() - 86400000L * 30 // last 30 days
        val timeOfDay = getTimeOfDay()

        // Fetch local data
        val allSongs = database.mostPlayedSongs(fromTimeStamp, limit = 100).first()
        val likedSongs = database.likedSongsByPlayTimeAsc().first()
        val skips = database.getAllSkips().first()
        val recentEvents = database.events().first().take(20)

        val likedIds = likedSongs.map { it.id }.toSet()
        val recentIds = recentEvents.mapNotNull { it.song?.id }.toSet()
        val skipMap = skips.associateBy { it.songId }

        // Score and sort all songs
        val scoredSongs = allSongs
            .map { song -> song to scoreSong(song, skipMap, likedIds, recentIds, timeOfDay) }
            .sortedByDescending { it.second }
            .map { it.first }

        // Use top scored songs as seeds for YouTube related songs
        val seedSongs = scoredSongs.take(5)

        val suggestions = mutableListOf<SongItem>()
        val seenIds = mutableSetOf<String>()

        for (seed in seedSongs) {
            currentCoroutineContext().ensureActive()
            if (suggestions.size >= MAX_SUGGESTIONS) break
            try {
                val endpoint = YouTube.next(
                    WatchEndpoint(videoId = seed.id)
                ).getOrNull()?.relatedEndpoint ?: continue

                val related = YouTube.related(endpoint).getOrNull() ?: continue

                val filtered = related.songs
                    .filterExplicit(hideExplicit)
                    .filterVideo(hideVideo)
                    .filter { it.id !in seenIds }
                    .shuffled()
                    .take(10)

                suggestions.addAll(filtered)
                seenIds.addAll(filtered.map { it.id })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportRecoverableException("ForYou", "load artist radio suggestions", error)
            }
        }

        // Fill remaining from liked songs radio if needed
        if (suggestions.size < MAX_SUGGESTIONS && likedSongs.isNotEmpty()) {
            val likedSeed = likedSongs.shuffled().firstOrNull()
            if (likedSeed != null) {
                try {
                    val endpoint = YouTube.next(
                        WatchEndpoint(videoId = likedSeed.id)
                    ).getOrNull()?.relatedEndpoint

                    if (endpoint != null) {
                        val related = YouTube.related(endpoint).getOrNull()
                        val filtered = related?.songs
                            ?.filterExplicit(hideExplicit)
                            ?.filterVideo(hideVideo)
                            ?.filter { it.id !in seenIds }
                            ?.shuffled()
                            ?.take(MAX_SUGGESTIONS - suggestions.size)
                            ?: emptyList()

                        suggestions.addAll(filtered)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportRecoverableException("ForYou", "load liked-song radio suggestions", error)
                }
            }
        }

        return suggestions.take(MAX_SUGGESTIONS)
    }

    /**
     * Record a skip for a song
     */
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
