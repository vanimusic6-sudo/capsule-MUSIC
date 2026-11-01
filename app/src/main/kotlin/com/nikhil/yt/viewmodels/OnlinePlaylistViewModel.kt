/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.utils.completed
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

    private val viewCountsMutex = Mutex()
    private val viewCountsInFlight = mutableSetOf<String>()
    // Metadata isn't part of playback. Limit concurrent requests so browsing a large
    // playlist cannot keep the radio/decoder busy for minutes in the background.
    private val viewCountsSemaphore = Semaphore(permits = 2)
    private var viewCountsJob: Job? = null
    private var lastVisibleIds = emptyList<String>()

    var continuation: String? = null
        private set

    init {
        load(initial = true)
    }

    fun refresh() {
        load(initial = false)
    }

    fun loadMoreSongs() {
        if (_isLoadingMore.value) return // Prevent multiple concurrent requests
        
        continuation?.let {
            viewModelScope.launch(Dispatchers.IO) {
                _isLoadingMore.value = true
                YouTube.playlistContinuation(it)
                    .onSuccess { playlistContinuationPage ->
                        val currentSongs = playlistSongs.value.toMutableList()
                        currentSongs.addAll(playlistContinuationPage.songs)
                        playlistSongs.value = currentSongs.distinctBy { it.id }
                        continuation = playlistContinuationPage.continuation
                        _isLoadingMore.value = false
                    }.onFailure { throwable ->
                        _isLoadingMore.value = false
                        reportException(throwable)
                    }
            }
        }
    }

    fun retry() {
        load(initial = true)
    }

    private fun load(initial: Boolean) {
        if (initial) {
            if (_isLoading.value && playlist.value != null) return
        } else {
            if (_isRefreshing.value || _isLoading.value) return
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (initial) {
                _isLoading.value = true
            } else {
                _isRefreshing.value = true
            }

            _error.value = null

            YouTube
                .playlist(playlistId)
                .completed()
                .onSuccess { playlistPage ->
                    playlist.value = playlistPage.playlist
                    playlistSongs.value = playlistPage.songs.distinctBy { it.id }
                    continuation = playlistPage.songsContinuation
                }.onFailure { throwable ->
                    _error.value = throwable.message ?: "Failed to load playlist"
                    reportException(throwable)
                }

            if (initial) {
                _isLoading.value = false
            } else {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Optional row counts are fetched only for settled, currently visible rows.
     * Never spawn one network job per track in the entire remote playlist:
     * a long playlist could create hundreds of requests and unnecessary heat.
     * A fling cancels the pending window, including its in-flight lookups.
     */
    fun prefetchViewCounts(videoIds: List<String>) {
        val visible = videoIds.distinct().filter(String::isNotBlank).take(8)
        if (visible == lastVisibleIds) return
        lastVisibleIds = visible
        viewCountsJob?.cancel()
        if (visible.isEmpty()) {
            viewCountsJob = null
            return
        }
        viewCountsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300L) // Wait for the finger/fling to settle before network metadata.
            coroutineScope {
                visible.map { videoId ->
                    async {
                        val shouldFetch = viewCountsMutex.withLock {
                            if (_viewCounts.value.containsKey(videoId) ||
                                videoId in viewCountsInFlight
                            ) false else {
                                viewCountsInFlight.add(videoId)
                                true
                            }
                        }
                        if (!shouldFetch) return@async
                        try {
                            viewCountsSemaphore.withPermit {
                                val count = YouTube.getMediaInfo(videoId).getOrNull()?.viewCount
                                if (count != null && count >= 0) {
                                    _viewCounts.update { current -> current + (videoId to count) }
                                }
                            }
                        } finally {
                            // Cancellation must not strand an ID in the in-flight set;
                            // otherwise subsequent viewport visits silently skip it.
                            withContext(NonCancellable) {
                                viewCountsMutex.withLock { viewCountsInFlight.remove(videoId) }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
