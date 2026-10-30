/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.ui.screens.artist.ArtistMemorialInfo
import com.nikhil.yt.ui.screens.artist.ArtistMemorialLookup
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.filterExplicit
import com.nikhil.yt.innertube.pages.ArtistPage
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.PlaylistItem
import kotlinx.coroutines.delay
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import javax.inject.Inject
import android.content.Context
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.extensions.filterExplicit
import com.nikhil.yt.extensions.filterExplicitAlbums
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    var artistPage by mutableStateOf<ArtistPage?>(null)
    var isLoading by mutableStateOf(true)
        private set
    private var loadJob: Job? = null
    private var memorialJob: Job? = null
    var memorialInfo by mutableStateOf<ArtistMemorialInfo?>(null)
        private set
    private var loadGeneration = 0L
    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val librarySongs = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()
        .flatMapLatest { hideExplicit ->
            database.artistSongsByCreateDateAsc(artistId).map { it.filterExplicit(hideExplicit) } // show all
            // database.artistSongsPreview(artistId).map { it.filterExplicit(hideExplicit) } // only preview
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()
        .flatMapLatest { hideExplicit ->
            database.artistAlbumsPreview(artistId).map { it.filterExplicitAlbums(hideExplicit) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // Load artist page and reload when hide explicit setting changes
        viewModelScope.launch {
            context.dataStore.data
                .map { it[HideExplicitKey] ?: false }
                .distinctUntilChanged()
                .collect {
                    fetchArtistsFromYTM()
                }
        }
    }

    fun fetchArtistsFromYTM() {
        loadJob?.cancel()
        val generation = ++loadGeneration
        isLoading = true
        loadJob = viewModelScope.launch {
            try {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                YouTube.artist(artistId)
                    .onSuccess { page ->
                        // MPLAUC identifies legitimate artist browse pages too. Filtering
                        // those sections removed official releases from the discography.
                        val filteredSections = page.sections.map { section ->
                            section.copy(items = section.items.filterExplicit(hideExplicit))
                        }

                        if (generation == loadGeneration) {
                            artistPage = page.copy(sections = filteredSections)

                            // Only album/playlist/episode previews are expanded. The first
                            // paint uses YouTube's fast preview; the full artist catalog is
                            // retrieved from the section's official browse endpoint without a
                            // broad search, aggressive parallel requests or artificial 3-item cap.
                            launch {
                                delay(260)
                                val candidates = filteredSections.withIndex().filter { (_, section) ->
                                    section.items.size in 1..6 &&
                                        section.moreEndpoint != null &&
                                        (section.items.any { it is AlbumItem || it is PlaylistItem } ||
                                            section.title.contains("episode", true) ||
                                            section.title.contains("выпуск", true) ||
                                            section.title.contains("эпизод", true) ||
                                            section.title.contains("single", true) ||
                                            section.title.contains("сингл", true))
                                }.take(4)

                                for ((sectionIndex, section) in candidates) {
                                    if (generation != loadGeneration) return@launch
                                    val endpoint = section.moreEndpoint ?: continue
                                    val firstPage = YouTube.artistItems(endpoint).getOrNull() ?: continue
                                    val found = firstPage.items.toMutableList()
                                    var token = firstPage.continuation
                                    val usedTokens = mutableSetOf<String>()
                                    // A handful of YTM pages is enough for typical artists;
                                    // cap work for very large catalogues and leave "More"
                                    // paginated instead of heating the phone in the background.
                                    var pages = 0
                                    while (token != null && found.size < 60 && pages < 2 &&
                                        generation == loadGeneration && usedTokens.add(token)
                                    ) {
                                        val next = YouTube.artistItemsContinuation(token).getOrNull()
                                            ?: break
                                        found += next.items
                                        token = next.continuation
                                        pages++
                                    }
                                    val merged = (section.items + found).distinctBy { it.id }
                                        .filterExplicit(hideExplicit)
                                    if (merged.size > section.items.size && generation == loadGeneration) {
                                        val current = artistPage ?: continue
                                        artistPage = current.copy(sections =
                                            current.sections.mapIndexed { index, existing ->
                                                if (index == sectionIndex) existing.copy(items = merged)
                                                else existing
                                            }
                                        )
                                    }
                                }
                            }
                            memorialJob?.cancel()
                            memorialJob = viewModelScope.launch {
                                val info = ArtistMemorialLookup.find(
                                    name = page.artist.title,
                                    channelId = page.artist.channelId,
                                    artistId = page.artist.id,
                                )
                                if (generation == loadGeneration) memorialInfo = info
                            }
                        }
                    }.getOrThrow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportException(error)
            } finally {
                if (generation == loadGeneration) isLoading = false
            }
        }
    }
}
