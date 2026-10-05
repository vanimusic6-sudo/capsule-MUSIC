/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.extensions.filterExplicit
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import com.nikhil.yt.di.PlayerCache
import com.nikhil.yt.di.DownloadCache
import androidx.media3.datasource.cache.Cache
import com.nikhil.yt.playback.audio.AudioCacheIdentity
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers

@HiltViewModel
class CachePlaylistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    @PlayerCache private val playerCache: Cache,
    @DownloadCache private val downloadCache: Cache
) : ViewModel() {

    private val observingScreen = MutableStateFlow(false)
    private val refreshMutex = Mutex()
    private val _cachedSongs = MutableStateFlow<List<Song>>(emptyList())
    val cachedSongs: StateFlow<List<Song>> = _cachedSongs

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // SongMenu may create this ViewModel without opening the cached playlist. Cache key
            // enumeration and Room queries have no reason to run until that screen is visible.
            observingScreen.collectLatest { visible ->
                if (!visible) return@collectLatest
                while (isActive) {
                    refreshCachedSongs()
                    delay(5_000L)
                }
            }
        }
    }

    fun setScreenVisible(visible: Boolean) {
        observingScreen.value = visible
    }

    private suspend fun refreshCachedSongs() {
        refreshMutex.withLock {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val cachedIds = playerCache.keys.map(AudioCacheIdentity::mediaId).toSet()
            val downloadedIds = downloadCache.keys.filter {
                AudioCacheIdentity.isComplete(downloadCache, it)
            }.toSet()
            val pureCacheIds = cachedIds.subtract(downloadedIds)

            val songs = if (pureCacheIds.isNotEmpty()) {
                database.getSongsByIds(pureCacheIds.toList())
            } else {
                emptyList()
            }

            val completeSongs = songs.filter {
                val contentLength = it.format?.contentLength
                AudioCacheIdentity.completeKey(playerCache, it.song.id, contentLength) != null
            }

            val completedAt = LocalDateTime.now()
            val undatedSongs = completeSongs.filter { it.song.dateDownload == null }
            if (undatedSongs.isNotEmpty()) {
                database.query {
                    undatedSongs.forEach {
                        update(it.song.copy(dateDownload = completedAt))
                    }
                }
            }

            _cachedSongs.value = completeSongs
                .map { if (it.song.dateDownload == null) it.copy(song = it.song.copy(dateDownload = completedAt)) else it }
                .sortedByDescending { it.song.dateDownload }
                .filterExplicit(hideExplicit)
        }
    }

    fun removeSongFromCache(songId: String) {
        AudioCacheIdentity.remove(playerCache, songId)
        if (observingScreen.value) {
            viewModelScope.launch(Dispatchers.IO) { refreshCachedSongs() }
        }
    }
}
