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
                        val filteredSections = page.sections
                            .filterNot { section ->
                                section.moreEndpoint?.browseId?.startsWith("MPLAUC") == true
                            }
                            .map { section ->
                                section.copy(items = section.items.filterExplicit(hideExplicit))
                            }

                        if (generation == loadGeneration) {
                            artistPage = page.copy(sections = filteredSections)

                            // YT Music's artist landing page only previews a few
                            // albums/playlists/episodes even when the "More" catalog has
                            // additional entries. Enrich only those small preview shelves.
                            // Keep the first paint fast and do not search the global catalog.
                            launch {
                                delay(240)
                                filteredSections.withIndex()
                                    .filter { (_, section) ->
                                        section.items.size in 1..4 &&
                                            section.moreEndpoint?.browseId != null &&
                                            (section.items.any { it is AlbumItem || it is PlaylistItem } ||
                                                section.title.contains("episode", true) ||
                                                section.title.contains("выпуск", true) ||
                                                section.title.contains("эпизод", true))
                                    }
                                    .take(4)
                                    .forEach { (sectionIndex, section) ->
                                        if (generation != loadGeneration) return@launch
                                        val endpoint = section.moreEndpoint ?: return@forEach
                                        val first = YouTube.artistItems(endpoint).getOrNull() ?: return@forEach
                                        val extra = first.items.toMutableList()
                                        if (extra.size < 25 && first.continuation != null) {
                                            YouTube.artistItemsContinuation(first.continuation)
                                                .getOrNull()?.let { extra += it.items }
                                        }
                                        val merged = (section.items + extra).distinctBy { it.id }
                                            .filterExplicit(hideExplicit).take(25)
                                        if (merged.size > section.items.size && generation == loadGeneration) {
                                            artistPage = artistPage?.copy(sections =
                                                artistPage?.sections?.mapIndexed { index, current ->
                                                    if (index == sectionIndex) current.copy(items = merged)
                                                    else current
                                                }.orEmpty()
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
