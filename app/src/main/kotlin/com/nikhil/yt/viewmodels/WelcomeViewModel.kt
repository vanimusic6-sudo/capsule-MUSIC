/**
 * Capsule MUSIC
 * The artists offered, searched and subscribed to during the welcome flow.
 * GPL-3.0
 */

package com.nikhil.yt.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.ArtistEntity
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.ArtistItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * How long a typed query rests before it is sent.
 *
 * Long enough that typing a name is one request rather than one per letter, short enough that it
 * does not read as lag.
 */
private const val SEARCH_DEBOUNCE_MS = 350L

/**
 * What fills the grid before anything has been typed.
 *
 * A first launch has no history to draw on, so there is nothing personal to offer and no honest way
 * to pretend otherwise. Charts are asked first because they are one request and are actually about
 * what people are listening to; these seeds are the fallback for when charts come back without
 * artists, which happens in some regions and when signed out.
 */
private val SEED_QUERIES = listOf("pop", "rock", "hip hop", "electronic", "indie")

private const val GRID_SIZE = 24

@HiltViewModel
class WelcomeViewModel
    @Inject
    constructor(
        private val database: MusicDatabase,
    ) : ViewModel() {
        private val _artists = MutableStateFlow<List<ArtistItem>>(emptyList())
        val artists = _artists.asStateFlow()

        private val _loading = MutableStateFlow(true)
        val loading = _loading.asStateFlow()

        /** Ids the user has picked. Held here so it survives the keyboard and rotation. */
        private val _selected = MutableStateFlow<Set<String>>(emptySet())
        val selected = _selected.asStateFlow()

        private var searchJob: Job? = null

        init {
            search("")
        }

        fun toggle(id: String) {
            _selected.value =
                _selected.value.let { if (id in it) it - id else it + id }
        }

        /** Replaces the grid with matches for [query], or the suggestions when it is blank. */
        fun search(query: String) {
            searchJob?.cancel()
            searchJob =
                viewModelScope.launch {
                    _loading.value = true
                    if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
                    val found =
                        if (query.isBlank()) suggestions() else matches(query)
                    // A failed request leaves whatever is on screen rather than emptying the grid.
                    if (found.isNotEmpty()) _artists.value = found
                    _loading.value = false
                }
        }

        /**
         * Subscribes to everything picked.
         *
         * Writes go through the same call the artist screen uses, so a subscription made here is
         * the same thing in every way — including being pushed to YouTube later if the user is
         * signed in.
         */
        suspend fun subscribeToSelection() {
            val picked = _selected.value
            if (picked.isEmpty()) return
            val chosen = _artists.value.filter { it.id in picked }
            withContext(Dispatchers.IO) {
                chosen.forEach { item ->
                    runCatching {
                        database.setArtistSubscribed(
                            ArtistEntity(
                                id = item.id,
                                name = item.title,
                                thumbnailUrl = item.thumbnail,
                                channelId = item.channelId,
                            ),
                            true,
                        )
                    }
                }
            }
        }

        private suspend fun suggestions(): List<ArtistItem> {
            val charted =
                runCatching { YouTube.getChartsPage().getOrNull() }
                    .getOrNull()
                    ?.sections
                    ?.flatMap { it.items }
                    ?.filterIsInstance<ArtistItem>()
                    .orEmpty()
            if (charted.isNotEmpty()) return charted.distinctBy { it.id }.take(GRID_SIZE)

            return viewModelScope
                .async {
                    SEED_QUERIES
                        .map { seed -> async { matches(seed) } }
                        .awaitAll()
                        .flatten()
                }.await()
                .distinctBy { it.id }
                .take(GRID_SIZE)
        }

        private suspend fun matches(query: String): List<ArtistItem> =
            runCatching {
                YouTube
                    .search(query, YouTube.SearchFilter.FILTER_ARTIST)
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<ArtistItem>()
                    .orEmpty()
            }.getOrDefault(emptyList())
                .distinctBy { it.id }
                .take(GRID_SIZE)
    }
