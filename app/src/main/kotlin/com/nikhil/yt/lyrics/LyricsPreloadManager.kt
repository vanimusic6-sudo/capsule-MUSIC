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
    
    // Track current queue to detect changes
    private var currentQueueIds: List<String> = emptyList()
    private var currentIndex: Int = -1

    /**
     * Called when the current song changes in the player.
     * Triggers pre-loading of lyrics for the next N songs in the queue.
     *
     * @param currentIndex The index of the currently playing song in the queue
     * @param queue List of MediaMetadata for songs in the queue
     */
    fun onSongChanged(currentIndex: Int, queue: List<MediaMetadata>) {
        // Cancel any existing preload job
        preloadJob?.cancel()
        
        // Check if pre-load is enabled
        preloadJob = scope.launch {
            try {
                val preferences = context.dataStore.data.first()
                val isEnabled = preferences[PreloadQueueLyricsEnabledKey] ?: true
                
                if (!isEnabled) {
                    Timber.tag(TAG).d("Queue lyrics pre-load is disabled")
                    return@launch
                }
                
                // Check network connectivity
                val isNetworkAvailable = try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }
                
                if (!isNetworkAvailable) {
                    Timber.tag(TAG).d("Network unavailable, skipping lyrics pre-load")
                    return@launch
                }
                
                val preloadCount = preferences[QueueLyricsPreloadCountKey] ?: DEFAULT_PRELOAD_COUNT
                
                // Get next N songs after current index
                val nextSongs = getNextSongs(queue, currentIndex, preloadCount)
                
                if (nextSongs.isEmpty()) {
                    Timber.tag(TAG).d("No songs to pre-load")
                    return@launch
                }
                
                Timber.tag(TAG).d("Starting pre-load for ${nextSongs.size} songs")
                preloadLyrics(nextSongs)
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
            }
        }
    }

    /**
     * Get the next N songs from the queue after the current index.
     */
    private fun getNextSongs(queue: List<MediaMetadata>, currentIndex: Int, count: Int): List<MediaMetadata> {
        if (queue.isEmpty() || currentIndex < 0) {
            return emptyList()
        }
        
        val startIndex = currentIndex + 1
        val endIndex = minOf(startIndex + count, queue.size)
        
        if (startIndex >= queue.size) {
            return emptyList()
        }
        
        return queue.subList(startIndex, endIndex)
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
