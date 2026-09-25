/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.filterExplicit
import com.nikhil.yt.innertube.models.filterVideo
import com.nikhil.yt.innertube.pages.SearchSummaryPage
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.constants.HideVideoKey
import com.nikhil.yt.models.ItemsPage
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = savedStateHandle.get<String>("query")!!
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    var soundCloudResult by
        mutableStateOf<SoundCloudCatalog.Result<SoundCloudCatalog.SearchPage>?>(null)
        private set

    private var soundCloudSearchJob: Job? = null

    init {
        viewModelScope.launch {
            filter.collect { filter ->
                if (filter == null) {
                    if (summaryPage == null) {
                        YouTube
                            .searchSummary(query)
                            .onSuccess {
                                summaryPage = it.filterExplicit(context.dataStore.get(HideExplicitKey, false)).filterVideo(context.dataStore.get(HideVideoKey, false))
                            }.onFailure {
                                reportException(it)
                            }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        YouTube
                            .search(query, filter)
                            .onSuccess { result ->
                                viewStateMap[filter.value] =
                                    ItemsPage(
                                        result.items
                                            .distinctBy { it.id }
                                            .filterExplicit(
                                                context.dataStore.get(
                                                    HideExplicitKey,
                                                    false
                                                )
                                            ).filterVideo(context.dataStore.get(HideVideoKey, false)),
                                        result.continuation,
                                    )
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    fun ensureSoundCloudSearch() {
        if (soundCloudResult != null || soundCloudSearchJob?.isActive == true) return

        soundCloudSearchJob = viewModelScope.launch {
            delay(280L)

            val initial = runInterruptible(Dispatchers.IO) {
                SoundCloudCatalog.search(query)
            }

            if (initial !is SoundCloudCatalog.Result.Success) {
                soundCloudResult = initial
                return@launch
            }

            var page = initial.value.copy(
                tracks = SoundCloudCatalog.validateTracks(
                    initial.value.tracks,
                ),
            )
            soundCloudResult = SoundCloudCatalog.Result.Success(page)

            var continuation = page.continuation
            var pagesLoaded = 0

            while (
                continuation != null &&
                pagesLoaded < MAX_SOUNDCLOUD_SEARCH_PAGES &&
                (
                    page.tracks.size < MAX_SOUNDCLOUD_SEARCH_TRACKS ||
                        page.users.size < MAX_SOUNDCLOUD_SEARCH_SECONDARY ||
                        page.playlists.size < MAX_SOUNDCLOUD_SEARCH_SECONDARY
                )
            ) {
                val request = continuation ?: break
                val more = runInterruptible(Dispatchers.IO) {
                    SoundCloudCatalog.searchMore(request)
                }
                if (more !is SoundCloudCatalog.Result.Success) break

                val playableTracks =
                    SoundCloudCatalog.validateTracks(
                        more.value.tracks,
                    )

                page = page.copy(
                    tracks = (page.tracks + playableTracks)
                        .distinctBy { it.permalink }
                        .take(MAX_SOUNDCLOUD_SEARCH_TRACKS),
                    users = (page.users + more.value.users)
                        .distinctBy { it.url }
                        .take(MAX_SOUNDCLOUD_SEARCH_SECONDARY),
                    playlists = (page.playlists + more.value.playlists)
                        .distinctBy { it.url }
                        .take(MAX_SOUNDCLOUD_SEARCH_SECONDARY),
                    continuation = more.value.continuation,
                )
                soundCloudResult = SoundCloudCatalog.Result.Success(page)
                continuation = more.value.continuation
                pagesLoaded++
            }
        }
    }

    fun loadMore() {
        val filter = filter.value?.value
        viewModelScope.launch {
            if (filter == null) return@launch
            val viewState = viewStateMap[filter] ?: return@launch
            val continuation = viewState.continuation
            if (continuation != null) {
                val searchResult =
                    YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
                viewStateMap[filter] = ItemsPage(
                    (viewState.items + searchResult.items).distinctBy { it.id },
                    searchResult.continuation
                )
            }
        }
    }
    private companion object {
        const val MAX_SOUNDCLOUD_SEARCH_TRACKS = 50
        const val MAX_SOUNDCLOUD_SEARCH_SECONDARY = 20
        const val MAX_SOUNDCLOUD_SEARCH_PAGES = 5
    }

}
