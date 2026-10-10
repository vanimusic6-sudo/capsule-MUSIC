/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.R
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.YTItem
import com.nikhil.yt.innertube.models.filterExplicit
import com.nikhil.yt.innertube.models.filterVideo
import com.nikhil.yt.innertube.pages.ExplorePage
import com.nikhil.yt.innertube.pages.HomePage
import com.nikhil.yt.innertube.utils.completed
import com.nikhil.yt.innertube.utils.parseCookieString
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.constants.HideVideoKey
import com.nikhil.yt.constants.InnerTubeCookieKey
import com.nikhil.yt.constants.QuickPicks
import com.nikhil.yt.constants.QuickPicksKey
import com.nikhil.yt.constants.YtmSyncKey
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.*
import com.nikhil.yt.extensions.toEnum
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.SyncUtils
import com.nikhil.yt.utils.reportException
import com.nikhil.yt.utils.reportRecoverableException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val syncUtils: SyncUtils,
    val forYouEngine: com.nikhil.yt.utils.ForYouSuggestionEngine,
) : ViewModel() {
    val isRefreshing = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)
    val isFilterLoading = MutableStateFlow(false)
    val filterLoadFailed = MutableStateFlow(false)
    private val isInitialLoadComplete = MutableStateFlow(false)
    val forYouSuggestions = MutableStateFlow<List<com.nikhil.yt.innertube.models.SongItem>?>(null)

    private val quickPicksEnum = context.dataStore.data.map {
        it[QuickPicksKey].toEnum(QuickPicks.QUICK_PICKS)
    }.distinctUntilChanged()

    val quickPicks = MutableStateFlow<List<Song>?>(null)
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    // This shelf represents songs to resume, never albums or artist pages.
    val keepListening = MutableStateFlow<List<Song>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    val homePage = MutableStateFlow<HomePage?>(null)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)

    private var unfilteredHomePage: HomePage? = null
    private var chipRequestJob: Job? = null
    private var loadMoreJob: Job? = null
    private var communityDiscoveryJob: Job? = null
    private val chipRequestSerial = java.util.concurrent.atomic.AtomicLong(0L)

    private data class CachedChip(val timestamp: Long, val page: HomePage)
    private val chipCache = LinkedHashMap<String, CachedChip>(12, 0.75f, true)
    private val chipCacheLock = Any()
    private fun findCachedChip(params: String): HomePage? =
        synchronized(chipCacheLock) {
            chipCache[params]?.takeIf { System.currentTimeMillis() - it.timestamp < 8 * 60_000L }?.page
        }
    private fun storeChip(params: String, page: HomePage) {
        if (page.sections.isEmpty()) return
        synchronized(chipCacheLock) {
            chipCache[params] = CachedChip(System.currentTimeMillis(), page)
            while (chipCache.size > 8) chipCache.remove(chipCache.keys.first())
        }
    }


    val recentActivity = MutableStateFlow<List<YTItem>?>(null)
    val recentPlaylistsDb = MutableStateFlow<List<Playlist>?>(null)

    val allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    val allYtItems = MutableStateFlow<List<YTItem>>(emptyList())

    // Account display info
    val accountName = MutableStateFlow<String?>(null)
    val accountImageUrl = MutableStateFlow<String?>(null)
    
    // Track last processed cookie to avoid unnecessary updates
    private var lastProcessedCookie: String? = null
    
    // Track if we're currently processing account data
    private var isProcessingAccountData = false
    private var wasLoggedIn = false

    private enum class HomeSectionKind {
        NEW_RELEASES,
        HITS,
        COMMUNITY_PLAYLISTS,
        LONG_LISTEN,
        PERSONALIZED,
        SHALLOW_SIMILARITY,
    }

    private fun classifyYouTubeHomeSection(section: HomePage.Section): HomeSectionKind? {
        val title =
            buildString {
                append(section.title)
                section.label?.let {
                    append(' ')
                    append(it)
                }
            }.lowercase()
        val browseId = section.endpoint?.browseId.orEmpty()
        val playlistOnly =
            section.items.isNotEmpty() &&
                section.items.all { it is PlaylistItem }

        // A one-artist "Similar to" shelf can appear after a single accidental play.
        if (
            listOf(
                "похоже на", "похожее на", "similar to",
                "because you listened to", "based on listening to",
            ).any(title::contains)
        ) return HomeSectionKind.SHALLOW_SIMILARITY

        // A community playlist stays a community playlist even if the title says "trending hits".
        if (
            playlistOnly &&
                listOf(
                    "community", "trending", "user playlist", "listener playlist",
                    "made by listeners", "made by fans", "плейлисты пользователей",
                    "пользовательские плейлисты", "плейлисты других пользователей",
                    "подборки других пользователей",
                    "плейлисты от пользователей",
                    "playlists by other users",
                    "other users playlists",
                    "playlists from other users",
                    "от других пользователей",
                    "от слушателей", "by other users", "by listeners",
                ).any(title::contains)
        ) return HomeSectionKind.COMMUNITY_PLAYLISTS

        // These are general recommendation shelves, not a specific mood filter.
        // YouTube returns them under some chips too; display them on the main Home only.
        val isPersonalized =
            (title == "for you" || title.endsWith(" for you") || title.startsWith("for you ")) ||
                listOf(
                "made for you",
                "recommended for you",
                "recommendations for you",
                "you might like",
                "you may like",
                "based on",
                "because you listened",
                "because you like",
                "similar to",
                "your mixes",
                "mixes for you",
                "playlists for you",
                "just for you",
                "для вас",
                "для тебя",
                "похоже на",
                "похожее на",
                "вам понравится",
                "по вашим вкусам",
                "на основе",
                "рекомендованные плейлисты",
                "рекомендации для",
            ).any(title::contains)
        if (isPersonalized) return HomeSectionKind.PERSONALIZED

        val isNewRelease =
            browseId == "FEmusic_new_releases_albums" ||
                listOf(
                    "new release",
                    "new releases",
                    "latest release",
                    "latest releases",
                    "новые релизы",
                    "новинки",
                    "свежие релизы",
                ).any(title::contains)

        if (isNewRelease) return HomeSectionKind.NEW_RELEASES

        val isHitSection =
            listOf(
                "hits",
                " hit",
                "хиты",
                "хит",
                "top ",
                "top-",
                "топ ",
                "топ-",
                "chart",
                "charts",
                "чарт",
            ).any(title::contains)

        if (isHitSection) return HomeSectionKind.HITS

        val isLongListen =
            listOf(
                "long listen",
                "long listens",
                "listen for a while",
                "listen awhile",
                "listen longer",
                "for a while",
                "want to listen for a while",
                "if you want to listen",
                "подольше",
                "надолго",
                "долго слушать",
                "долгое прослушивание",
                "если хочется послушать подольше",
            ).any(title::contains)

        if (isLongListen) return HomeSectionKind.LONG_LISTEN

        val isCommunityPlaylists =
            playlistOnly &&
                listOf(
                    "community",
                    "from the",
                    "trending",
                    "user playlist",
                    "user playlists",
                    "listener playlist",
                    "listener playlists",
                    "made by listeners",
                    "made by fans",
                    "music fans",
                    "сообщество",
                    "от сообщества",
                    "плейлисты пользователей",
                    "пользовательские плейлисты",
                    "от слушателей",
                    "плейлисты других пользователей",
                    "подборки других пользователей",
                    "плейлисты от пользователей",
                    "playlists by other users",
                    "other users playlists",
                    "playlists from other users",
                    "от других пользователей",
                    "от пользователей",
                    "by other users",
                    "by listeners",
                    "other listeners",
                    "playlists from",
                    "слушатели",
                ).any(title::contains)

        return if (isCommunityPlaylists) {
            HomeSectionKind.COMMUNITY_PLAYLISTS
        } else {
            null
        }
    }

    private fun cleanYouTubeHomePage(
        page: HomePage,
        hideExplicit: Boolean,
        hideVideo: Boolean,
        filteredFeed: Boolean = false,
        chips: List<HomePage.Chip>? = page.chips,
    ): HomePage {
        val sections =
            page.sections.mapNotNull { section ->
                val kind = classifyYouTubeHomeSection(section)
                if (kind == HomeSectionKind.SHALLOW_SIMILARITY) return@mapNotNull null
                if (!filteredFeed && kind == null) return@mapNotNull null
                // The selected provider endpoint already restricts tracks to the chip.
                // Removing rows by broad names like "New releases" left valid filters empty.
                // Route only obvious, global, playlist-only collections to normal Home.
                val globallyRecommendedPlaylists =
                    section.items.isNotEmpty() &&
                        section.items.all { it is PlaylistItem } &&
                        (kind == HomeSectionKind.COMMUNITY_PLAYLISTS ||
                            kind == HomeSectionKind.PERSONALIZED)
                if (filteredFeed && globallyRecommendedPlaylists) return@mapNotNull null

                val items =
                    section.items
                        .filterExplicit(hideExplicit)
                        .filterVideo(hideVideo)
                        .filter { item ->
                            when {
                                filteredFeed ->
                                    item is SongItem ||
                                        item is AlbumItem ||
                                        item is PlaylistItem

                                kind == HomeSectionKind.COMMUNITY_PLAYLISTS ->
                                    item is PlaylistItem

                                kind == HomeSectionKind.PERSONALIZED ->
                                    item is SongItem ||
                                        item is AlbumItem ||
                                        item is PlaylistItem

                                kind == HomeSectionKind.LONG_LISTEN ->
                                    item is SongItem ||
                                        item is AlbumItem ||
                                        item is PlaylistItem

                                else ->
                                    item is SongItem ||
                                        item is AlbumItem
                            }
                        }
                        .distinctBy { item ->
                            when (item) {
                                is SongItem -> "song:${item.id}"
                                is AlbumItem -> "album:${item.id}"
                                is PlaylistItem -> "playlist:${item.id}"
                                else -> "item:${item.id}"
                            }
                        }

                if (items.isEmpty()) return@mapNotNull null

                val title =
                    when (kind) {
                        HomeSectionKind.COMMUNITY_PLAYLISTS ->
                            context.getString(R.string.home_community_playlists)

                        HomeSectionKind.LONG_LISTEN ->
                            context.getString(R.string.home_long_listens)

                        else -> section.title
                    }

                section.copy(
                    title = title,
                    items = items,
                )
            }

        return page.copy(
            chips = chips,
            sections = sections,
        )
    }

    /**
     * Generic community and personalised playlist-only shelves belong to normal Home.
     * Preserve actual provider items and remove copies from themed filter responses.
     */
    private fun collectGeneralPlaylists(
        page: HomePage,
        hideExplicit: Boolean,
        hideVideo: Boolean,
    ) {
        val base = unfilteredHomePage ?: return
        val sections =
            cleanYouTubeHomePage(
                page = page,
                hideExplicit = hideExplicit,
                hideVideo = hideVideo,
            ).sections.filter { section ->
                section.items.isNotEmpty() &&
                    section.items.all { it is PlaylistItem } &&
                    (
                        section.title == context.getString(R.string.home_community_playlists) ||
                            classifyYouTubeHomeSection(section) == HomeSectionKind.PERSONALIZED
                    )
            }
        if (sections.isEmpty()) return

        val updated = base.sections.toMutableList()
        for (candidate in sections) {
            val found = updated.indexOfFirst { it.title.equals(candidate.title, ignoreCase = true) }
            if (found < 0) {
                updated.add(candidate.copy(items = candidate.items.distinctBy { it.id }.take(30)))
            } else {
                val current = updated[found]
                val seen = current.items.mapTo(mutableSetOf()) { it.id }
                val added = candidate.items.filter { seen.add(it.id) }
                if (added.isNotEmpty()) {
                    updated[found] = current.copy(items = (current.items + added).take(30))
                }
            }
        }
        unfilteredHomePage = base.copy(sections = updated)
        if (selectedChip.value == null) {
            homePage.value = unfilteredHomePage
            refreshAllYouTubeItems()
        }
    }

    /**
     * The unfiltered YouTube endpoint may not include user playlists at all.
     * Discover them once using at most five existing chip endpoints. No polling.
     */
    private fun discoverCommunityPlaylists(
        base: HomePage,
        hideExplicit: Boolean,
        hideVideo: Boolean,
    ) {
        communityDiscoveryJob?.cancel()
        val communityTitle = context.getString(R.string.home_community_playlists)
        if (unfilteredHomePage?.sections?.any { it.title == communityTitle } == true) return

        val chipParams = base.chips.orEmpty()
            .mapNotNull { it.endpoint?.params }
            .distinct()
            .take(3)
        if (chipParams.isEmpty()) return

        communityDiscoveryJob = viewModelScope.launch(Dispatchers.IO) {
            // Weak networks cannot handle simultaneous background browse requests.
            // Foreground chip requests always win and cancel this deferred prefetch.
            delay(4_000L)
            for (params in chipParams) {
                if (selectedChip.value != null || isFilterLoading.value || isLoading.value) return@launch
                val next = try {
                    withTimeout(12_000L) { YouTube.home(params = params).getOrThrow() }
                } catch (timeout: TimeoutCancellationException) {
                    Timber.w("Home playlist discovery timed out")
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.w(error, "Home playlist discovery failed")
                    null
                } ?: continue
                storeChip(
                    params,
                    cleanYouTubeHomePage(
                        page = next,
                        hideExplicit = hideExplicit,
                        hideVideo = hideVideo,
                        filteredFeed = true,
                        chips = base.chips,
                    ),
                )
                collectGeneralPlaylists(next, hideExplicit, hideVideo)
                if (unfilteredHomePage?.sections?.any { it.title == communityTitle } == true) break
                delay(1_500L)
            }
        }
    }

    private fun refreshAllYouTubeItems() {
        val filteredItems =
            homePage.value?.sections?.flatMap { it.items }.orEmpty()
        allYtItems.value =
            if (selectedChip.value == null) {
                forYouSuggestions.value.orEmpty() + filteredItems
            } else {
                filteredItems
            }
    }

    private suspend fun getQuickPicks(){
        when (quickPicksEnum.first()) {
            QuickPicks.QUICK_PICKS -> quickPicks.value = database.quickPicks().first().shuffled().take(20)
            QuickPicks.LAST_LISTEN -> songLoad()
        }
    }

    private suspend fun load() {
        if (isLoading.value) return

        chipRequestJob?.cancel()
        loadMoreJob?.cancel()
        communityDiscoveryJob?.cancel()
        chipRequestSerial.incrementAndGet()
        synchronized(chipCacheLock) { chipCache.clear() }
        isFilterLoading.value = false
        filterLoadFailed.value = false
        selectedChip.value = null
        unfilteredHomePage = null

        isLoading.value = true
        
        try {
            supervisorScope {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideVideo = context.dataStore.get(HideVideoKey, false)
                val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2

                launch { getQuickPicks() }
                launch { forgottenFavorites.value = database.forgottenFavorites().first().shuffled().take(20) }
                launch {
                    try {
                        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                        val hideVideo = context.dataStore.get(HideVideoKey, false)
                        forYouSuggestions.value = forYouEngine.getSuggestions(hideExplicit, hideVideo)
                    } catch (error: Exception) {
                        reportRecoverableException("HomeViewModel", "load For You suggestions", error)
                    }
                }
                
                launch {
                    keepListening.value = database.mostPlayedSongs(fromTimeStamp, limit = 15, offset = 5)
                        .first().distinctBy { it.id }.shuffled().take(12)
                }

                launch {
                    YouTube.home().onSuccess { page ->
                        val cleaned =
                            cleanYouTubeHomePage(
                                page = page,
                                hideExplicit = hideExplicit,
                                hideVideo = hideVideo,
                            )
                        unfilteredHomePage = cleaned
                        homePage.value = cleaned
                        discoverCommunityPlaylists(page, hideExplicit, hideVideo)
                    }.onFailure { reportException(it) }
                }

                launch {
                    YouTube.explore().onSuccess { page ->
                        val artists: MutableMap<Int, String> = mutableMapOf()
                        val favouriteArtists: MutableMap<Int, String> = mutableMapOf()
                        database.allArtistsByPlayTime().first().let { list ->
                            var favIndex = 0
                            for ((artistsIndex, artist) in list.withIndex()) {
                                artists[artistsIndex] = artist.id
                                if (artist.artist.bookmarkedAt != null) {
                                    favouriteArtists[favIndex] = artist.id
                                    favIndex++
                                }
                            }
                        }
                        explorePage.value = page.copy(
                            newReleaseAlbums = page.newReleaseAlbums
                                .sortedBy { album ->
                                    val artistIds = album.artists.orEmpty().mapNotNull { it.id }
                                    val firstArtistKey = artistIds.firstNotNullOfOrNull { artistId ->
                                        if (artistId in favouriteArtists.values) {
                                            favouriteArtists.entries.firstOrNull { it.value == artistId }?.key
                                        } else {
                                            artists.entries.firstOrNull { it.value == artistId }?.key
                                        }
                                    } ?: Int.MAX_VALUE
                                    firstArtistKey
                                }.filterExplicit(hideExplicit)
                        )
                    }.onFailure { reportException(it) }
                }
            }

            allLocalItems.value =
                (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
                    .distinctBy { it.id }

            refreshAllYouTubeItems()
                    
            isInitialLoadComplete.value = true
        } catch (e: Exception) {
            reportException(e)
        } finally {
            isLoading.value = false
        }
    }

    private suspend fun songLoad() {
        val song = database.events().first().firstOrNull()?.song
        if (song != null) {
            if (database.hasRelatedSongs(song.id)) {
                val relatedSongs = database.getRelatedSongs(song.id).first().shuffled().take(20)
                quickPicks.value = relatedSongs
            }
        }
    }

    private val _isLoadingMore = MutableStateFlow(false)

    fun loadMoreYouTubeItems(continuation: String?) {
        if (continuation == null || _isLoadingMore.value) return

        val selectedAtStart = selectedChip.value
        val filteredFeed = selectedAtStart != null
        val chips = homePage.value?.chips

        loadMoreJob?.cancel()
        loadMoreJob =
            viewModelScope.launch(Dispatchers.IO) {
                _isLoadingMore.value = true
                try {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideo = context.dataStore.get(HideVideoKey, false)
                    val nextSections =
                        YouTube.home(continuation).getOrNull()
                            ?: return@launch

                    if (selectedChip.value != selectedAtStart) return@launch

                    val merged =
                        nextSections.copy(
                            chips = chips,
                            sections = homePage.value?.sections.orEmpty() + nextSections.sections,
                        )

                    if (filteredFeed) {
                        collectGeneralPlaylists(nextSections, hideExplicit, hideVideo)
                    }

                    val cleaned =
                        cleanYouTubeHomePage(
                            page = merged,
                            hideExplicit = hideExplicit,
                            hideVideo = hideVideo,
                            filteredFeed = filteredFeed,
                            chips = chips,
                        )

                    if (selectedChip.value != selectedAtStart) return@launch

                    homePage.value = cleaned
                    if (!filteredFeed) {
                        unfilteredHomePage = cleaned
                    }
                    refreshAllYouTubeItems()
                } finally {
                    _isLoadingMore.value = false
                }
            }
    }

    fun toggleChip(chip: HomePage.Chip?) {
        if (chip == null || chip == selectedChip.value) {
            chipRequestSerial.incrementAndGet()
            chipRequestJob?.cancel()
            loadMoreJob?.cancel()
            isFilterLoading.value = false
            filterLoadFailed.value = false
            selectedChip.value = null
            unfilteredHomePage?.let { base ->
                homePage.value = base
                refreshAllYouTubeItems()
            }
            return
        }

        // User intent outranks the auxiliary community-playlist discovery.
        communityDiscoveryJob?.cancel()
        loadFilteredChip(chip, useCache = true)
    }

    fun retrySelectedChip() {
        val chip = selectedChip.value ?: return
        communityDiscoveryJob?.cancel()
        loadFilteredChip(chip, useCache = false)
    }

    private fun loadFilteredChip(chip: HomePage.Chip, useCache: Boolean) {
        val params = chip.endpoint?.params ?: return
        val base = unfilteredHomePage ?: homePage.value
        if (unfilteredHomePage == null) unfilteredHomePage = base

        chipRequestSerial.incrementAndGet()
        val requestId = chipRequestSerial.get()
        chipRequestJob?.cancel()
        loadMoreJob?.cancel()
        selectedChip.value = chip
        filterLoadFailed.value = false

        val cached = if (useCache) findCachedChip(params) else null
        if (cached != null) {
            homePage.value = cached.copy(chips = base?.chips ?: cached.chips)
            isFilterLoading.value = false
            refreshAllYouTubeItems()
            return
        }

        // Empty content is a loading state, not a completed empty filter.
        isFilterLoading.value = true
        homePage.value = base?.copy(sections = emptyList(), continuation = null)
        refreshAllYouTubeItems()

        chipRequestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Coalesce fast taps so only the last selected chip hits the network.
                delay(280L)
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideVideo = context.dataStore.get(HideVideoKey, false)
                var lastError: Throwable? = null
                repeat(2) { attempt ->
                    val page = try {
                        withTimeout(15_000L) {
                            YouTube.home(params = params).getOrThrow()
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        lastError = timeout
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        lastError = error
                        null
                    }

                    if (requestId != chipRequestSerial.get() || selectedChip.value != chip) return@launch
                    if (page != null && page.sections.isNotEmpty()) {
                        val cleaned = cleanYouTubeHomePage(
                            page = page,
                            hideExplicit = hideExplicit,
                            hideVideo = hideVideo,
                            filteredFeed = true,
                            chips = base?.chips ?: page.chips,
                        )
                        storeChip(params, cleaned)
                        collectGeneralPlaylists(page, hideExplicit, hideVideo)
                        homePage.value = cleaned
                        filterLoadFailed.value = false
                        refreshAllYouTubeItems()
                        return@launch
                    }
                    if (attempt == 0) delay(650L)
                }

                if (requestId == chipRequestSerial.get() && selectedChip.value == chip) {
                    filterLoadFailed.value = true
                    lastError?.let { reportRecoverableException("HomeViewModel", "load Home filter", it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestId == chipRequestSerial.get() && selectedChip.value == chip) {
                    filterLoadFailed.value = true
                    reportRecoverableException("HomeViewModel", "process Home filter", error)
                }
            } finally {
                if (requestId == chipRequestSerial.get() && selectedChip.value == chip) {
                    isFilterLoading.value = false
                }
            }
        }
    }

    fun refresh() {
        if (isRefreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            isRefreshing.value = true
            load()
            isRefreshing.value = false
        }
    }

    fun refreshAccountData() {
        viewModelScope.launch(Dispatchers.IO) {
            if (isProcessingAccountData) return@launch
            
            isProcessingAccountData = true
            try {
                val cookie = context.dataStore.get(InnerTubeCookieKey, "")
                if (cookie.isNotEmpty()) {
                    YouTube.cookie = cookie
                    
                    YouTube.accountInfo().onSuccess { info ->
                        accountName.value = info.name
                        accountImageUrl.value = info.thumbnailUrl
                    }.onFailure {
                        timber.log.Timber.w(it, "Failed to fetch account info")
                    }

                    launch {
                        YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
                            val lists = it.items.filterIsInstance<PlaylistItem>().filterNot { it.id == "SE" }
                            accountPlaylists.value = lists
                        }.onFailure {
                            timber.log.Timber.w(it, "Failed to fetch playlists")
                        }
                    }
                } else {
                    accountName.value = "Guest"
                    accountImageUrl.value = null
                    accountPlaylists.value = null
                }
            } finally {
                isProcessingAccountData = false
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            load()
        }

        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(3000)
            
            syncUtils.cleanupDuplicatePlaylists()
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { it[InnerTubeCookieKey] }
                .distinctUntilChanged()
                .collect { cookie ->
                    if (isProcessingAccountData) return@collect
                    
                    lastProcessedCookie = cookie
                    isProcessingAccountData = true
                    
                    try {
                        val isLoggedIn = cookie?.let { "SAPISID" in parseCookieString(it) } ?: false
                        val loginTransition = isLoggedIn && !wasLoggedIn
                        wasLoggedIn = isLoggedIn
                        
                        if (isLoggedIn && cookie != null && cookie.isNotEmpty()) {
                            try {
                                YouTube.cookie = cookie
                            } catch (e: Exception) {
                                timber.log.Timber.e(e, "Failed to set YouTube cookie")
                                return@collect
                            }

                            if (loginTransition) {
                                launch {
                                    try {
                                        if (context.dataStore.get(YtmSyncKey, true)) {
                                            syncUtils.performFullSync()
                                        }
                                    } catch (e: Exception) {
                                        Timber.e(e, "Error during login-triggered sync")
                                        reportException(e)
                                    }
                                }
                            }
                            
                            kotlinx.coroutines.delay(100)
                            
                            try {
                                YouTube.accountInfo().onSuccess { info ->
                                    accountName.value = info.name
                                    accountImageUrl.value = info.thumbnailUrl
                                }.onFailure { e ->
                                    timber.log.Timber.w(e, "Failed to fetch account info")
                                }
                            } catch (e: Exception) {
                                timber.log.Timber.e(e, "Exception fetching account info")
                            }

                            launch {
                                try {
                                    YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
                                        val lists = it.items.filterIsInstance<PlaylistItem>().filterNot { it.id == "SE" }
                                        accountPlaylists.value = lists
                                    }.onFailure { e ->
                                        timber.log.Timber.w(e, "Failed to fetch account playlists")
                                    }
                                } catch (e: Exception) {
                                    timber.log.Timber.e(e, "Exception fetching account playlists")
                                }
                            }
                        } else {
                            accountName.value = "Guest"
                            accountImageUrl.value = null
                            accountPlaylists.value = null
                        }
                    } catch (e: Exception) {
                        timber.log.Timber.e(e, "Error processing cookie change")
                        accountName.value = "Guest"
                        accountImageUrl.value = null
                        accountPlaylists.value = null
                    } finally {
                        isProcessingAccountData = false
                    }
                }
        }
    }
}
