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
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
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
        viewModelScope.launch {
            filter.collectLatest { filter ->
                if (filter == null) {
                    if (summaryPage == null) {
                        YouTube
                            .searchSummary(query)
                            .onSuccess {
                                summaryPage = it.filterExplicit(context.dataStore.get(HideExplicitKey, false))
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
                                            ),
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

    fun ensureExperimentalSearch(source: ExperimentalCatalog.Source) {
        if (experimentalResults[source] != null) return
        experimentalSearchJob?.cancel()
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
