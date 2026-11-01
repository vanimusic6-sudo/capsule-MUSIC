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
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

sealed interface AlbumUiState {
    data object Loading : AlbumUiState

    data object Content : AlbumUiState

    data object Empty : AlbumUiState

    data class Error(
        val isNotFound: Boolean = false,
    ) : AlbumUiState
}

private sealed interface FetchState {
    data object Pending : FetchState
    data object Success : FetchState
    data class Failed(val isNotFound: Boolean = false) : FetchState
}

@HiltViewModel
class AlbumViewModel
@Inject
constructor(
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val albumId = savedStateHandle.get<String>("albumId")!!
    val playlistId = MutableStateFlow("")
    val albumWithSongs =
        database
            .albumWithSongs(albumId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    var otherVersions = MutableStateFlow<List<AlbumItem>>(emptyList())

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Pending)

    val uiState: StateFlow<AlbumUiState> =
        combine(albumWithSongs, _fetchState) { data, fetch ->
            resolveAlbumUiState(data != null, data?.songs?.isNotEmpty() == true,
                fetch is FetchState.Pending, fetch is FetchState.Failed,
                (fetch as? FetchState.Failed)?.isNotFound == true)

        }.stateIn(viewModelScope, SharingStarted.Eagerly, AlbumUiState.Loading)

    private var fetchJob: Job? = null

    init {
        retry()
    }

    fun retry() {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            _fetchState.value = FetchState.Pending
            try {
                val previous = database.album(albumId).first()
                val page = YouTube.album(albumId).getOrThrow()
                playlistId.value = page.album.playlistId
                otherVersions.value = page.otherVersions
                database.withTransaction {
                    if (previous == null) insert(page) else update(previous.album, page, previous.artists)
                }
                _fetchState.value = FetchState.Success
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                reportException(error)
                // A remote miss must not delete a cached/bookmarked collection.
                _fetchState.value = FetchState.Failed(error.message?.contains("NOT_FOUND") == true)
            }
        }
    }

}

/** Cached metadata without tracks must terminate in Empty/Error, never a stuck spinner. */
internal fun resolveAlbumUiState(hasMetadata: Boolean, hasSongs: Boolean,
    pending: Boolean, failed: Boolean, notFound: Boolean = false): AlbumUiState = when {
    hasSongs -> AlbumUiState.Content
    pending -> AlbumUiState.Loading
    failed -> AlbumUiState.Error(notFound)
    !hasMetadata -> AlbumUiState.Loading
    else -> AlbumUiState.Empty
}
