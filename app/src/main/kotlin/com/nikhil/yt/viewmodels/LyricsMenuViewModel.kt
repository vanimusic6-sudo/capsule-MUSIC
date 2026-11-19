/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.LyricsEntity
import com.nikhil.yt.lyrics.LyricsHelper
import com.nikhil.yt.lyrics.LyricsResult
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.utils.NetworkConnectivityObserver
import com.nikhil.yt.utils.reportRecoverableException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
 
import javax.inject.Inject

@HiltViewModel
class LyricsMenuViewModel
@Inject
constructor(
    private val lyricsHelper: LyricsHelper,
    val database: MusicDatabase,
    private val networkConnectivity: NetworkConnectivityObserver,
) : ViewModel() {
    private var job: Job? = null
    @Volatile
    private var searchGeneration: Long = 0L
    val results = MutableStateFlow(emptyList<LyricsResult>())
    val isLoading = MutableStateFlow(false)

    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            networkConnectivity.networkStatus.collect { isConnected ->
                _isNetworkAvailable.value = isConnected
            }
        }
        
        // Set initial state using synchronous check
        _isNetworkAvailable.value = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true // Assume connected as fallback
        }
    }

    fun search(
        mediaId: String,
        title: String,
        artist: String,
        duration: Int,
    ) {
        job?.cancel()
        val generation = ++searchGeneration
        results.value = emptyList()
        isLoading.value = true
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                lyricsHelper.getAllLyrics(mediaId, title, artist, null, duration) { result ->
                    // Discard an old, late source result if a new track/search is active.
                    if (generation == searchGeneration) {
                        results.update { current ->
                            if (current.any {
                                    it.providerName == result.providerName &&
                                        it.lyrics == result.lyrics
                                }) current else current + result
                        }
                    }
                }
            } finally {
                if (generation == searchGeneration) isLoading.value = false
            }
        }
    }

    fun cancelSearch() {
        ++searchGeneration
        job?.cancel()
        job = null
        isLoading.value = false
    }

    fun refetchLyrics(
        mediaMetadata: MediaMetadata,
        lyricsEntity: LyricsEntity?,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata, forceRefresh = true)
                database.query {
                    lyricsEntity?.let(::delete)
                    upsert(LyricsEntity(mediaMetadata.id, lyrics))
                }
            } catch (error: Exception) {
                reportRecoverableException("LyricsMenu", "refetch lyrics", error)
            }
        }
    }

    fun updateLyrics(
        mediaMetadata: MediaMetadata,
        lyrics: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            database.query {
                upsert(LyricsEntity(mediaMetadata.id, lyrics))
            }
        }
    }
}
