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
import kotlinx.coroutines.delay
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
    private var loadMoreJob: Job? = null
    private var tasteRecommendationsJob: Job? = null
    private var exploreJob: Job? = null

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

        // General recommendation shelves belong directly to the Home feed.
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

    /**
     * Normal Home retains the existing curated Capsule sections (new releases, hits,
     * community playlists, long listening and personal shelves). There are no longer
     * themed chip feeds to classify, cache or merge.
     */
    private fun cleanYouTubeHomePage(
        page: HomePage,
        hideExplicit: Boolean,
        hideVideo: Boolean,
    ): HomePage {
        val sections = page.sections.mapNotNull { section ->
            val kind = classifyYouTubeHomeSection(section)
            if (kind == null || kind == HomeSectionKind.SHALLOW_SIMILARITY) {
                return@mapNotNull null
            }

            val items = section.items
                .filterExplicit(hideExplicit)
                .filterVideo(hideVideo)
                .filter { item ->
                    when (kind) {
                        HomeSectionKind.COMMUNITY_PLAYLISTS -> item is PlaylistItem
                        HomeSectionKind.PERSONALIZED,
                        HomeSectionKind.LONG_LISTEN ->
                            item is SongItem || item is AlbumItem || item is PlaylistItem
                        else -> item is SongItem || item is AlbumItem
                    }
                }
                .distinctBy { it.id }

            if (items.isEmpty()) return@mapNotNull null
            val title = when (kind) {
                HomeSectionKind.COMMUNITY_PLAYLISTS ->
                    context.getString(R.string.home_community_playlists)
                HomeSectionKind.LONG_LISTEN ->
                    context.getString(R.string.home_long_listens)
                else -> section.title
            }
            section.copy(title = title, items = items)
        }
        return page.copy(sections = sections)
    }

    private fun refreshAllYouTubeItems() {
        allYtItems.value =
            forYouSuggestions.value.orEmpty() +
                homePage.value?.sections.orEmpty().flatMap { it.items }
    }

    private suspend fun getQuickPicks(){
        when (quickPicksEnum.first()) {
            QuickPicks.QUICK_PICKS -> quickPicks.value = database.quickPicks().first().shuffled().take(20)
            QuickPicks.LAST_LISTEN -> songLoad()
        }
    }

    private suspend fun load() {
        if (isLoading.value) return

        loadMoreJob?.cancel()
        _isLoadingMore.value = false
        tasteRecommendationsJob?.cancel()
        exploreJob?.cancel()

        isLoading.value = true
        
        try {
            supervisorScope {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideVideo = context.dataStore.get(HideVideoKey, false)
                val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2

                launch { getQuickPicks() }
                launch { forgottenFavorites.value = database.forgottenFavorites().first().shuffled().take(20) }
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
                        homePage.value = cleaned
                    }.onFailure { reportException(it) }
                }

            }

            allLocalItems.value =
                (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
                    .distinctBy { it.id }

            // MetroList's initial Home only waits for cheap DB data and the primary feed.
            // Recommendations (up to several "related" requests) and Explore are non-critical.
            tasteRecommendationsJob = viewModelScope.launch(Dispatchers.IO) {
                delay(8_000L)
                try {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideo = context.dataStore.get(HideVideoKey, false)
                    forYouSuggestions.value = forYouEngine.getSuggestions(hideExplicit, hideVideo)
                    refreshAllYouTubeItems()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportRecoverableException("HomeViewModel", "load For You suggestions", error)
                }
            }

            exploreJob = viewModelScope.launch(Dispatchers.IO) {
                delay(2_000L)
                try {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportRecoverableException("HomeViewModel", "load Explore", error)
                }
            }

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
        _isLoadingMore.value = true
        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideVideo = context.dataStore.get(HideVideoKey, false)
                val next = YouTube.home(continuation = continuation).getOrNull()
                    ?: return@launch
                val current = homePage.value
                // The only feed is the default Home. Preserve its sections and append
                // provider continuations without overwriting newer refresh results.
                if (current?.continuation != continuation) return@launch
                val merged = next.copy(
                    sections = current.sections + next.sections,
                )
                homePage.value = cleanYouTubeHomePage(
                    page = merged,
                    hideExplicit = hideExplicit,
                    hideVideo = hideVideo,
                )
                refreshAllYouTubeItems()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportRecoverableException("HomeViewModel", "load Home continuation", error)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun refresh() {
        if (isRefreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            isRefreshing.value = true
            try {
                load()
            } finally {
                isRefreshing.value = false
            }
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

                    // Home no longer fetches library playlists; Library owns that query.
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

                            // Library loads the user's playlists on demand, not Home.
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
