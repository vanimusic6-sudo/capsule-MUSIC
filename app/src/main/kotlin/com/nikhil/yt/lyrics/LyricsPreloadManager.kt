/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.lyrics

import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.nikhil.yt.constants.PreloadQueueLyricsEnabledKey
import com.nikhil.yt.constants.QueueLyricsPreloadCountKey
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.LyricsEntity
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.utils.NetworkConnectivityObserver
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.reportException
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Manages pre-loading of lyrics for upcoming songs in the queue.
 * This improves user experience by having lyrics ready when songs change.
 */
class LyricsPreloadManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val database: MusicDatabase,
    private val networkConnectivity: NetworkConnectivityObserver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preloadJob: Job? = null
    private val preloadMissBlockedUntil = ConcurrentHashMap<String, Long>()
    
    /**
     * Called when the current song changes in the player.
     * Triggers pre-loading of lyrics for the next N songs in the queue.
     *
     * [nextSongs] runs on Main only after admission and receives the configured count.
     */
    fun onSongChanged(nextSongs: (Int) -> List<MediaMetadata>) {
        preloadJob?.cancel()
        preloadJob = scope.launch {
            try {
                preloadSettledLyrics(
                    readCount = {
                        val preferences = context.dataStore.data.first()
                        val enabled = preferences[PreloadQueueLyricsEnabledKey] ?: true
                        val connected = try { networkConnectivity.isCurrentlyConnected() }
                            catch (_: Exception) { true }
                        if (enabled && connected) preferences[QueueLyricsPreloadCountKey] ?: DEFAULT_PRELOAD_COUNT else 0
                    },
                    // ExoPlayer's queue may only be read on its application thread.
                    nextSongs = { count -> withContext(Dispatchers.Main.immediate) { nextSongs(count) } },
                    preload = ::preloadLyrics,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportException(failure)
            }
        }
    }

    /**
     * Pre-load lyrics for the given songs.
     * Uses one cancellable request chain to avoid duplicate background work.
     */
    private suspend fun preloadLyrics(songs: List<MediaMetadata>) {
        // Process songs sequentially. One tracked coroutine means a queue
        // change cancels every outstanding network request instead of leaving
        // an untracked launcher behind.
        songs.forEach { song ->
            val now = System.currentTimeMillis()
            val blockedUntil = preloadMissBlockedUntil[song.id] ?: 0L
            if (blockedUntil > now) {
                Timber.tag(TAG).d("Skipping recent lyrics miss for: ${song.title}")
                return@forEach
            }
            if (blockedUntil != 0L) preloadMissBlockedUntil.remove(song.id, blockedUntil)

            val existingLyrics = database.lyrics(song.id).first()
            if (existingLyrics != null) {
                Timber.tag(TAG).d(
                    if (existingLyrics.lyrics == LyricsEntity.LYRICS_NOT_FOUND) {
                        "Lyrics already known to be unavailable for: ${song.title}"
                    } else {
                        "Lyrics already cached for: ${song.title}"
                    },
                )
                return@forEach
            }

            try {
                val lyrics = fetchLyricsForSong(song)
                if (lyrics != null && lyrics != LyricsEntity.LYRICS_NOT_FOUND) {
                    preloadMissBlockedUntil.remove(song.id)
                    database.query {
                        upsert(
                            LyricsEntity(
                                id = song.id,
                                lyrics = lyrics,
                            ),
                        )
                    }
                    Timber.tag(TAG).d("Pre-loaded lyrics for: ${song.title}")
                } else {
                    preloadMissBlockedUntil[song.id] = now + PRELOAD_MISS_COOLDOWN_MS
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).d(
                    "Failed to pre-load lyrics for ${song.title}: ${e.message}",
                )
            }
        }
    }

    /**
     * Fetch lyrics for a single song using the LyricsHelper.
     * This is a simplified version that gets lyrics from enabled providers.
     */
    private suspend fun fetchLyricsForSong(song: MediaMetadata): String? {
        val lyricsHelper = LyricsHelper(context, networkConnectivity)
        
        return try {
            lyricsHelper.getLyrics(song, preferredProviderOnly = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).d("Error fetching lyrics for ${song.title}: ${e.message}")
            null
        }
    }

    /**
     * Cancel any ongoing preload operations.
     */
    fun cancel() {
        preloadJob?.cancel()
        preloadJob = null
    }

    /**
     * Clean up resources when no longer needed.
     */
    fun destroy() {
        cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "LyricsPreloadManager"
        private const val DEFAULT_PRELOAD_COUNT = 1
        private const val PRELOAD_MISS_COOLDOWN_MS = 30 * 60 * 1_000L
    }
}
