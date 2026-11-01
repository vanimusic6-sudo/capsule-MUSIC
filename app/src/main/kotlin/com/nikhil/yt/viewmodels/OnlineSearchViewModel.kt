/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nikhil.yt.utils.GlobalLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.filterExplicit
import com.nikhil.yt.innertube.pages.SearchSummaryPage
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.models.ItemsPage
import com.nikhil.yt.discovery.ExperimentalCatalog
import com.nikhil.yt.constants.ExperimentalSourcesEnabledKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    // The default Results tab is published atomically. A one-shelf response
    // must never appear as a finished search and then push everything down.
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
        private set
    var summaryRequestFailed by mutableStateOf(false)
        private set
    private var summaryJob: Job? = null
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    sealed interface ExperimentalSearchState {
        data object Loading : ExperimentalSearchState
        data class Ready(val tracks: List<ExperimentalCatalog.Track>) : ExperimentalSearchState
        data class Failed(val message: String) : ExperimentalSearchState
    }

    val experimentalResults = mutableStateMapOf<ExperimentalCatalog.Source, ExperimentalSearchState>()
    private var experimentalSearchJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        // Unlike collectLatest on the selected chip, this work is NOT cancelled
        // when the user briefly visits Songs or Albums. One complete result is
        // delivered in one Compose state write; the UI never shifts its shelves
        // underneath a finger.
        retrySummary()
        viewModelScope.launch {
            filter.collectLatest { selectedFilter ->
                if (selectedFilter == null || viewStateMap[selectedFilter.value] != null) {
                    return@collectLatest
                }
                YouTube.search(query, selectedFilter)
                    .onSuccess { result ->
                        val hideExplicit = withContext(Dispatchers.IO) {
                            context.dataStore.get(HideExplicitKey, false)
                        }
                        viewStateMap[selectedFilter.value] = ItemsPage(
                            result.items.distinctBy { it.id }.filterExplicit(hideExplicit),
                            result.continuation,
                        )
                    }.onFailure { reportException(it) }
            }
        }
    }

    /**
     * Preserve the speed improvement from concurrent Songs/Albums requests,
     * but present the *complete* summary at once. Errors terminate the skeleton
     * and offer retry instead of leaving an infinite loading state.
     */
    fun retrySummary() {
        if (summaryPage != null || summaryJob?.isActive == true) return
        summaryRequestFailed = false
        summaryJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val hideExplicit = withContext(Dispatchers.IO) {
                    context.dataStore.get(HideExplicitKey, false)
                }
                // This callback is diagnostic only; it does NOT change UI state.
                val response = withContext(Dispatchers.Default) {
                    YouTube.searchSummary(query, onPrimary = { primary ->
                        if (GlobalLog.isEnabled) {
                            GlobalLog.append(Log.DEBUG, "SearchLatency",
                                "primaryMs=${SystemClock.elapsedRealtime() - startedAt} shelves=${primary.summaries.size}")
                        }
                    })
                }.getOrThrow()
                val completed = withContext(Dispatchers.Default) {
                    val filtered = response.filterExplicit(hideExplicit)
                    filtered.withFilteredSongCredits(
                        filtered.summaries.flatMap { it.items }.filterIsInstance<SongItem>()
                    )
                }
                // One observable update = one settled content layout.
                summaryPage = completed
                if (GlobalLog.isEnabled) {
                    GlobalLog.append(Log.DEBUG, "SearchLatency",
                        "completeMs=${SystemClock.elapsedRealtime() - startedAt} shelves=${completed.summaries.size}")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                summaryRequestFailed = true
                reportException(error)
            }
        }
    }

    fun ensureExperimentalSearch(source: ExperimentalCatalog.Source) {
        if (experimentalResults[source] != null) return
        experimentalSearchJob?.cancel()
        // A cancelled previous source must not remain stuck in Loading on return.
        experimentalResults.entries.removeAll { (otherSource, state) ->
            otherSource != source && state is ExperimentalSearchState.Loading
        }
        experimentalResults[source] = ExperimentalSearchState.Loading
        experimentalSearchJob = viewModelScope.launch {
            try {
                delay(220L)
                if (!context.dataStore.get(ExperimentalSourcesEnabledKey, false)) return@launch
                val tracks = ExperimentalCatalog.search(source, query)
                if (context.dataStore.get(ExperimentalSourcesEnabledKey, false)) {
                    experimentalResults[source] = ExperimentalSearchState.Ready(tracks)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                experimentalResults[source] =
                    ExperimentalSearchState.Failed(failure.message ?: "Source unavailable")
            }
        }
    }

    fun retryExperimentalSearch(source: ExperimentalCatalog.Source) {
        experimentalSearchJob?.cancel()
        experimentalResults.remove(source)
        ensureExperimentalSearch(source)
    }

    fun disableExperimentalSearch() {
        experimentalSearchJob?.cancel()
        experimentalResults.clear()
    }

    fun loadMore() {
        if (loadMoreJob?.isActive == true) return

        val filterValue = filter.value?.value ?: return
        val viewState = viewStateMap[filterValue] ?: return
        val continuation = viewState.continuation ?: return

        loadMoreJob =
            viewModelScope.launch {
                val searchResult =
                    YouTube.searchContinuation(
                        continuation,
                        selectedFromVideoResults = filterValue == YouTube.SearchFilter.FILTER_VIDEO.value,
                    ).getOrNull() ?: return@launch
                viewStateMap[filterValue] =
                    ItemsPage(
                        (viewState.items + searchResult.items).distinctBy { it.id },
                        searchResult.continuation,
                    )
            }
    }

}
