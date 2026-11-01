/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import android.os.SystemClock
import android.util.Log
import com.nikhil.yt.utils.GlobalLog
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

@HiltViewModel
class OnlinePlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase
) : ViewModel() {
    private val playlistId = savedStateHandle.get<String>("playlistId")!!

    val playlist = MutableStateFlow<PlaylistItem?>(null)
    val playlistSongs = MutableStateFlow<List<SongItem>>(emptyList())
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()
    
    val dbPlaylist = database.playlistByBrowseId(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _viewCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val viewCounts = _viewCounts.asStateFlow()

    private var viewCountsJob: Job? = null
    private var visibleCountIds = emptyList<String>()
    private val viewCountsSemaphore = Semaphore(permits = 2)

    private var loadJob: Job? = null
    private var pageJob: Job? = null
    @Volatile private var generation = 0L
    private val usedCursors = mutableSetOf<String>()

    var continuation: String? = null
        private set

    init {
        load(initial = true)
    }

    fun refresh() {
        load(initial = false)
    }

    fun loadMoreSongs() {
        if (_isLoadingMore.value || _isLoading.value || _isRefreshing.value) return
        val cursor = continuation ?: return
        val owner = generation
        _isLoadingMore.value = true
        pageJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = YouTube.playlistContinuation(cursor).getOrThrow()
                if (owner != generation) return@launch
                usedCursors += cursor
                continuation = page.continuation?.takeUnless { it in usedCursors }
                playlistSongs.value = (playlistSongs.value + page.songs).distinctBy { it.id }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (owner == generation) reportException(error)
            } finally {
                if (owner == generation) _isLoadingMore.value = false
            }
        }
    }

    fun retry() {
        load(initial = true)
    }

    private fun load(initial: Boolean) {
        if (loadJob?.isActive == true) return
        val owner = ++generation
        pageJob?.cancel()
        _isLoadingMore.value = false
        if (initial) _isLoading.value = true else _isRefreshing.value = true
        _error.value = null
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val started = SystemClock.elapsedRealtime()
                val page = YouTube.playlist(playlistId).getOrThrow()
                if (GlobalLog.isEnabled) GlobalLog.append(Log.DEBUG, "DetailLatency",
                    "playlistFirstPageMs=${SystemClock.elapsedRealtime() - started} rows=${page.songs.size} more=${page.songsContinuation != null}")
                if (owner != generation) return@launch
                usedCursors.clear()
                continuation = page.songsContinuation
                playlist.value = page.playlist
                playlistSongs.value = page.songs.distinctBy { it.id }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (owner == generation) {
                    _error.value = error.message ?: "Failed to load playlist"
                    reportException(error)
                }
            } finally {
                if (owner == generation) {
                    _isLoading.value = false
                    _isRefreshing.value = false
                }
            }
        }
    }

    fun prefetchViewCounts(videoIds: List<String>) {
        val uniqueIds = videoIds.distinct().filter { it.isNotBlank() }.take(8)
        if (uniqueIds == visibleCountIds) return
        visibleCountIds = uniqueIds
        viewCountsJob?.cancel()
        if (uniqueIds.isEmpty()) { viewCountsJob = null; return }
        viewCountsJob = viewModelScope.launch(Dispatchers.IO) {
            // Optional metadata starts only after the viewport settles. A fling
            // must not enqueue player requests for hundreds of off-screen rows.
            delay(250)
            coroutineScope {
                uniqueIds.filterNot { _viewCounts.value.containsKey(it) }.map { videoId ->
                    async {
                        viewCountsSemaphore.withPermit {
                            val count = YouTube.getMediaInfo(videoId).getOrNull()?.viewCount
                            if (count != null && count >= 0) {
                                _viewCounts.update { current -> current + (videoId to count) }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
