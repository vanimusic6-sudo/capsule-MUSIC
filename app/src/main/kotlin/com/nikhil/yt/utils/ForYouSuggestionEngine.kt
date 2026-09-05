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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
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

    private val lookupMutex = Mutex()
    private data class RelatedCacheEntry(val songs: List<SongItem>, val expiresAt: Long)
    private val relatedCache = LinkedHashMap<List<Any?>, RelatedCacheEntry>(16, 0.75f, true)

    private suspend fun relatedSongs(seedId: String): List<SongItem> = lookupMutex.withLock {
        val key = listOf(seedId, YouTube.cookie, YouTube.visitorData, YouTube.locale, YouTube.proxy)
        relatedCache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withLock it.songs }
        val endpoint = YouTube.next(WatchEndpoint(videoId = seedId)).getOrNull()?.relatedEndpoint
            ?: return@withLock emptyList()
        val songs = YouTube.related(endpoint).getOrNull()?.songs ?: return@withLock emptyList()
        relatedCache[key] = RelatedCacheEntry(songs, System.currentTimeMillis() + 5 * 60_000L)
        while (relatedCache.size > 32) relatedCache.remove(relatedCache.keys.first())
        songs
    }

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
            skipCount = skipMap[song.id]?.let {
                RecommendationFeedback.effectiveSkips(
                    it.skipCount, it.lastSkippedAt,
                    song.song.likedDate?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
                    System.currentTimeMillis(),
                )
            } ?: 0,
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

        val knownSongs = (allSongs + likedSongs).associateBy { it.id }
        fun effectiveSkips(id: String): Int = skipMap[id]?.let {
            RecommendationFeedback.effectiveSkips(
                it.skipCount, it.lastSkippedAt,
                knownSongs[id]?.song?.likedDate?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
                System.currentTimeMillis(),
            )
        } ?: 0

        // Score and sort all songs
        val scoredSongs = allSongs.filter { effectiveSkips(it.id) < 2 }
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
                val filtered = relatedSongs(seed.id)
                    .filterExplicit(hideExplicit)
                    .filterVideo(hideVideo)
                    .filter { it.id !in seenIds && effectiveSkips(it.id) < 2 }
                    .take(15)

                suggestions.addAll(filtered)
                seenIds.addAll(filtered.map { it.id })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportRecoverableException("ForYou", "load artist radio suggestions", error)
            }
        }

        if (suggestions.size < MAX_SUGGESTIONS) {
            val seedIds = seedSongs.map { it.id }.toSet()
            val likedSeed = likedSongs.firstOrNull { it.id !in seedIds && effectiveSkips(it.id) < 2 }
            if (likedSeed != null) {
                suggestions += relatedSongs(likedSeed.id).filterExplicit(hideExplicit).filterVideo(hideVideo)
            }
        }
        return RecommendationFeedback.select(
            candidates = suggestions,
            limit = MAX_SUGGESTIONS,
            id = { it.id },
            artistIds = { song -> song.artists.map { it.id ?: it.name.lowercase() } },
            rejected = { effectiveSkips(it.id) >= 2 },
            score = { candidate ->
                knownSongs[candidate.id]?.let { scoreSong(it, skipMap, likedIds, recentIds, timeOfDay) / 4f }
                    ?: 0f
            },
        )
    }

    /**
     * Record a skip for a song
     */
    suspend fun recordSkip(songId: String) = database.withTransaction {
        val now = System.currentTimeMillis()
        val existing = database.getSkip(songId)
        if (existing != null) {
            val likedAt = database.song(songId).first()?.song?.likedDate
                ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            val remainingSkips = RecommendationFeedback.effectiveSkips(existing.skipCount, existing.lastSkippedAt, likedAt, now)
            database.upsertSkip(
                existing.copy(
                    skipCount = (remainingSkips + 1).coerceAtMost(20),
                    lastSkippedAt = now,
                )
            )
        } else {
            database.upsertSkip(SongSkipEntity(songId = songId, skipCount = 1, lastSkippedAt = now))
        }
    }
}
