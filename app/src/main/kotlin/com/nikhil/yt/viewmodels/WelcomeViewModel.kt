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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

        /**
         * The artists picked, kept whole rather than as ids.
         *
         * Ids were the bug. The grid is replaced by every search, so an artist picked before the
         * next search was no longer in it — and subscribing worked by filtering the *current* grid
         * by the picked ids, which quietly threw away everyone found by an earlier query. Search a
         * name, tap, search the next name, tap, and only the last one was ever subscribed.
         *
         * Keeping the item means a pick is complete the moment it is made and does not depend on
         * anything still being on screen.
         */
        private val _selected = MutableStateFlow<Map<String, ArtistItem>>(emptyMap())
        val selected = _selected.asStateFlow()

        private var searchJob: Job? = null

        init {
            search("")
        }

        fun toggle(artist: ArtistItem) {
            _selected.value =
                _selected.value.let {
                    if (artist.id in it) it - artist.id else it + (artist.id to artist)
                }
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
            val chosen = _selected.value.values.toList()
            if (chosen.isEmpty()) return
            /*
             * One awaited transaction, not a fan-out of fire-and-forget ones.
             *
             * setArtistSubscribed posts its work to Room's transaction executor and returns, which
             * is fine on a screen that stays open — but this screen closes the instant it returns,
             * so the writes were racing the teardown of the thing that ordered them. withTransaction
             * suspends until they are actually committed, and does all of them at once.
             */
            database.withTransaction {
                chosen.forEach { item ->
                    runCatching {
                        setArtistBookmarked(
                            ArtistEntity(
                                id = item.id,
                                name = item.title,
                                thumbnailUrl = item.thumbnail,
                                channelId = item.channelId,
                            ),
                            true,
                        )?.syncSubscription()
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
