/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.BrowseEndpoint
import com.nikhil.yt.innertube.models.filterExplicit
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.models.ItemsPage
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistItemsViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val browseId = savedStateHandle.get<String>("browseId")!!
    private val params = savedStateHandle.get<String>("params")

    private var loadingContinuation = false

    val title = MutableStateFlow("")
    val itemsPage = MutableStateFlow<ItemsPage?>(null)

    init {
        viewModelScope.launch {
            YouTube
                .artistItems(
                    BrowseEndpoint(
                        browseId = browseId,
                        params = params,
                    ),
                ).onSuccess { artistItemsPage ->
                    title.value = artistItemsPage.title
                    itemsPage.value =
                        ItemsPage(
                            items = artistItemsPage.items
                                .distinctBy { it.id }
                                .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                ,
                            continuation = artistItemsPage.continuation,
                        )
                }.onFailure {
                    reportException(it)
                }
        }
    }

    fun loadMore() {
        if (loadingContinuation) return
        val initial = itemsPage.value ?: return
        val token = initial.continuation ?: return
        loadingContinuation = true
        viewModelScope.launch {
            try {
                YouTube.artistItemsContinuation(token)
                    .onSuccess { page ->
                        itemsPage.update { current ->
                            // Drop stale responses when navigation or a refresh changed
                            // the continuation while the request was in flight.
                            val snapshot = current ?: return@update current
                            if (snapshot.continuation != token) return@update current
                            ItemsPage(
                                items = (snapshot.items + page.items)
                                    .distinctBy { it.id }
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false)),
                                continuation = if (page.continuation == token) null else page.continuation,
                            )
                        }
                    }.onFailure { reportException(it) }
            } finally {
                loadingContinuation = false
            }
        }
    }

}
