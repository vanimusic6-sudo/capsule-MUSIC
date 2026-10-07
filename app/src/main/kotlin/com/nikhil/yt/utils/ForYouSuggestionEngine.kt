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
import kotlin.math.ln
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capsule taste recommendation engine.
 *
 * A recommendation seed must be supported by real listening behaviour. Following an artist or
 * touching a song once is not enough. Repeated listening and completion-equivalent play time raise
 * confidence; skips reduce it. When there is not enough evidence, Home deliberately shows no
 * personalised shelf instead of fabricating one.
 */
@Singleton
class ForYouSuggestionEngine @Inject constructor(
    private val database: MusicDatabase
) {

    companion object {
        const val MAX_SUGGESTIONS = 24
        private const val MAX_SEED_SONGS = 5
        private const val MIN_SEED_SONGS = 2
        private const val MIN_EQUIVALENT_PLAYS = 1.25
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

    private fun hasEnoughListeningEvidence(
        song: Song,
        playCount: Int,
        windowPlayTimeMs: Long,
    ): Boolean {
        val durationMs =
            (song.song.duration.takeIf { it > 0 } ?: 180)
                .coerceAtLeast(30) * 1_000.0
        val equivalentPlays = windowPlayTimeMs.coerceAtLeast(0L) / durationMs

        return (playCount >= 2 && equivalentPlays >= MIN_EQUIVALENT_PLAYS) ||
            equivalentPlays >= 2.0 ||
            playCount >= 4
    }

    private fun scoreSong(
        song: Song,
        windowPlayTimeMs: Long,
        playCount: Int,
        skipMap: Map<String, SongSkipEntity>,
        likedIds: Set<String>,
        recentIds: Set<String>,
        timeOfDay: String,
    ): Float {
        val base =
            RecommendationScore.calculate(
                totalPlayTimeMs = windowPlayTimeMs,
                durationSeconds = song.song.duration,
                skipCount = skipMap[song.id]?.skipCount ?: 0,
                liked = song.id in likedIds,
                recent = song.id in recentIds,
                timeOfDay = timeOfDay,
            )
        // Repeat count is a separate signal from raw play time: deliberately replaying a track
        // should matter more than leaving one very long track running once.
        val repeatBonus = (ln(1.0 + playCount.coerceAtLeast(0)) * 0.9).toFloat()
        return base + repeatBonus
    }

    /**
     * Build one high-confidence taste shelf. Sources are intentionally limited to listening
     * behaviour; subscriptions and a single accidental play are not seeds.
     */
    suspend fun getSuggestions(
        hideExplicit: Boolean = false,
        hideVideo: Boolean = false,
    ): List<SongItem> {
        val fromTimeStamp = System.currentTimeMillis() - 86400000L * 30
        val timeOfDay = getTimeOfDay()

        val allSongs = database.mostPlayedSongs(fromTimeStamp, limit = 100).first()
        val statsById =
            database.mostPlayedSongsStats(fromTimeStamp, limit = 100)
                .first()
                .associateBy { it.id }
        val likedIds = database.likedSongsByPlayTimeAsc().first().map { it.id }.toSet()
        val skipMap = database.getAllSkips().first().associateBy { it.songId }
        val recentIds = database.events().first().take(24).map { it.song.id }.toSet()

        val seedSongs =
            allSongs
                .mapNotNull { song ->
                    val stats = statsById[song.id] ?: return@mapNotNull null
                    val windowPlayTime = stats.timeListened ?: 0L
                    if (!hasEnoughListeningEvidence(song, stats.songCountListened, windowPlayTime)) {
                        return@mapNotNull null
                    }
                    song to scoreSong(
                        song = song,
                        windowPlayTimeMs = windowPlayTime,
                        playCount = stats.songCountListened,
                        skipMap = skipMap,
                        likedIds = likedIds,
                        recentIds = recentIds,
                        timeOfDay = timeOfDay,
                    )
                }
                .filter { (_, score) -> score > 0f }
                .sortedByDescending { (_, score) -> score }
                .map { (song, _) -> song }
                .take(MAX_SEED_SONGS)

        if (seedSongs.size < MIN_SEED_SONGS) return emptyList()

        val seedIds = seedSongs.mapTo(mutableSetOf()) { it.id }
        val relatedBuckets = mutableListOf<List<SongItem>>()

        for (seed in seedSongs) {
            currentCoroutineContext().ensureActive()
            try {
                val endpoint =
                    YouTube.next(WatchEndpoint(videoId = seed.id))
                        .getOrNull()
                        ?.relatedEndpoint
                        ?: continue
                val related = YouTube.related(endpoint).getOrNull() ?: continue
                val bucket =
                    related.songs
                        .filterExplicit(hideExplicit)
                        .filterVideo(hideVideo)
                        .filter { it.id !in seedIds }
                        .distinctBy { it.id }
                        .take(10)
                if (bucket.isNotEmpty()) relatedBuckets += bucket
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportRecoverableException("TasteRecommendations", "load related songs", error)
            }
        }

        if (relatedBuckets.size < MIN_SEED_SONGS) return emptyList()

        // Interleave sources so one favourite track cannot dominate the whole shelf.
        val suggestions = mutableListOf<SongItem>()
        val seenIds = seedIds.toMutableSet()
        val longestBucket = relatedBuckets.maxOfOrNull { it.size } ?: 0
        for (index in 0 until longestBucket) {
            for (bucket in relatedBuckets) {
                val item = bucket.getOrNull(index) ?: continue
                if (seenIds.add(item.id)) suggestions += item
                if (suggestions.size >= MAX_SUGGESTIONS) return suggestions
            }
        }

        return suggestions
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
