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
import com.nikhil.yt.innertube.pages.ExplorePage
import com.nikhil.yt.innertube.pages.HomePage
import com.nikhil.yt.innertube.utils.completed
import com.nikhil.yt.innertube.utils.parseCookieString
import com.nikhil.yt.constants.HideExplicitKey
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap
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
    val homeFeedError = MutableStateFlow(false)
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
    // Fresh catalog releases come from YouTube Music's dedicated albums endpoint.
    val newReleaseAlbums = MutableStateFlow<List<AlbumItem>>(emptyList())
    // Real public user-created playlists, returned by YouTube Music's community filter.
    val communityPlaylists = MutableStateFlow<List<PlaylistItem>>(emptyList())
    private var loadMoreJob: Job? = null
    private var tasteRecommendationsJob: Job? = null
    private var exploreJob: Job? = null
    private var communityPlaylistsJob: Job? = null
    private val attemptedContinuations = ConcurrentHashMap.newKeySet<String>()
    private val continuationBudget = 10
    private var homeTasteArtists: Set<String> = emptySet()

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
        TRENDING,
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

        if (listOf("trending", "трендовое", "в тренде", "популярное").any(title::contains)) {
            return HomeSectionKind.TRENDING
        }

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
     * YouTube Music already provides the shelves for this account. Do not guess which
     * localized section titles are "allowed": filtering unknown titles removed mixes,
     * personal albums and playlists by other users, leaving Home almost empty.
     *
     * Filter invalid/explicit content, not legitimate discovery categories.
     */
    private fun cleanYouTubeHomePage(
        page: HomePage,
        hideExplicit: Boolean,
    ): HomePage {
        val sections = page.sections.mapNotNull { section ->
            val items = section.items
                .filterExplicit(hideExplicit)
                .filter { item ->
                    item is SongItem || item is AlbumItem ||
                        item is PlaylistItem || item is com.nikhil.yt.innertube.models.ArtistItem
                }
                .distinctBy { it.id }
            if (items.isEmpty()) null else section.copy(items = items)
        }.distinctBy { section ->
            // The provider can repeat the same shelf in continuation pages.
            section.title to section.items.first().id
        }
        return page.copy(sections = sections)
    }

    // Put likely personal material first without changing the order each
    // time the user fetches the next page. Browsing categories are still
    // retained as discovery, but generic charts no longer dominate the top.
    private fun sectionInterest(
        section: HomePage.Section,
        familiarArtists: Set<String>,
    ): Int {
        val categoryPriority = when (classifyYouTubeHomeSection(section)) {
            HomeSectionKind.PERSONALIZED -> 60
            HomeSectionKind.NEW_RELEASES -> 47
            HomeSectionKind.LONG_LISTEN -> 43
            HomeSectionKind.COMMUNITY_PLAYLISTS -> 32
            HomeSectionKind.HITS -> 24
            HomeSectionKind.TRENDING -> 12
            else -> 0
        }
        if (familiarArtists.isEmpty()) return categoryPriority
        val overlaps = section.items.count { item ->
            val names = when (item) {
                is SongItem -> item.artists.map { it.name }
                is AlbumItem -> item.artists.orEmpty().map { it.name }
                else -> emptyList()
            }
            names.any { it.trim().lowercase() in familiarArtists }
        }
        // A generic "for you" heading is not proof of personalized music.
        // Without recognizable taste anchors, it should not eclipse a good
        // new-release shelf. Discovery still remains available lower down.
        val relevantPriority =
            if (categoryPriority == 60 && overlaps == 0) 22 else categoryPriority
        return relevantPriority + overlaps.coerceAtMost(5) * 8
    }

    private fun refreshAllYouTubeItems() {
        allYtItems.value =
            forYouSuggestions.value.orEmpty() +
                homePage.value?.sections.orEmpty().flatMap { it.items }
    }

    // Community playlists are a different catalog from YouTube Music's personalized
    // Home shelves. Query its public-playlist filter with the user's actual taste
    // instead of renaming a generic mix as a listener-created playlist.
    private suspend fun loadCommunityPlaylists(artistSeeds: List<String>) {
        val requests = artistSeeds
            .map { "$it playlist" }
            .distinct()
            .take(2)
            .ifEmpty { listOf("music playlist") }
        val results = mutableListOf<PlaylistItem>()
        for (query in requests) {
            val playlists = YouTube.search(
                query,
                YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
            ).getOrNull()?.items.orEmpty().filterIsInstance<PlaylistItem>()
            results.addAll(playlists)
            if (results.distinctBy { it.id }.size >= 8) break
        }
        // No listening history, or no matches for the first two artists:
        // use one real YouTube Music public-playlist search as a fallback.
        if (results.size < 3 && requests != listOf("music playlist")) {
            results.addAll(
                YouTube.search(
                    "music playlist",
                    YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
                ).getOrNull()?.items.orEmpty().filterIsInstance<PlaylistItem>(),
            )
        }
        communityPlaylists.value = results
            .filter { it.id.isNotBlank() && !it.thumbnail.isNullOrBlank() }
            .distinctBy { it.id }
            .take(18)
    }

    private suspend fun getQuickPicks(){
        when (quickPicksEnum.first()) {
            QuickPicks.QUICK_PICKS -> quickPicks.value = database.quickPicks().first().distinctBy { it.id }.take(20)
            QuickPicks.LAST_LISTEN -> songLoad()
        }
    }

    private suspend fun load() {
        if (isLoading.value) return

        loadMoreJob?.cancel()
        _isLoadingMore.value = false
        attemptedContinuations.clear()
        tasteRecommendationsJob?.cancel()
        exploreJob?.cancel()
        communityPlaylistsJob?.cancel()

        isLoading.value = true
        homeFeedError.value = false

        try {
            supervisorScope {
                // Use one preference snapshot before the first network request, rather
                // than racing the cookie observer and occasionally requesting a guest feed.
                val preferences = context.dataStore.data.first()
                val hideExplicit = preferences[HideExplicitKey] ?: false
                val startupCookie = preferences[InnerTubeCookieKey]
                if (YouTube.cookie != startupCookie) YouTube.cookie = startupCookie
                val fromTimeStamp = System.currentTimeMillis() - 86400000L * 14
                val locallyPlayed = database.mostPlayedSongs(fromTimeStamp, limit = 45)
                    .first().distinctBy { it.id }
                val familiarArtists = locallyPlayed
                    .filter { it.song.totalPlayTime >= 90_000L || it.song.liked }
                    .flatMap { song -> song.artists.map { it.name.trim().lowercase() } }
                    .filter { it.isNotBlank() }.toSet()
                homeTasteArtists = familiarArtists

                launch { getQuickPicks() }
                launch { forgottenFavorites.value = database.forgottenFavorites().first().distinctBy { it.id }.take(20) }
                launch { keepListening.value = database.recentlyListenedSongs(limit = 16).first() }

                launch {
                    YouTube.home().onSuccess { page ->
                        var cleaned = cleanYouTubeHomePage(page, hideExplicit)
                        // Some provider pages contain no usable shelves but do have a
                        // continuation. Advance only then, with a strict request budget.
                        // A populated first page incurs no additional requests.
                        var checkedContinuations = 0
                        while (cleaned.sections.isEmpty() &&
                            cleaned.continuation != null &&
                            checkedContinuations < 2
                        ) {
                            val token = cleaned.continuation ?: break
                            val nextResult = YouTube.home(continuation = token)
                            if (nextResult.isFailure) {
                                homeFeedError.value = true
                                nextResult.exceptionOrNull()?.let(::reportException)
                                break
                            }
                            val next = nextResult.getOrThrow()
                            val repeatedToken = next.continuation == token
                            cleaned = cleanYouTubeHomePage(
                                page = cleaned.copy(
                                    sections = cleaned.sections + next.sections,
                                    continuation = next.continuation,
                                ),
                                hideExplicit = hideExplicit,
                            )
                            checkedContinuations++
                            if (repeatedToken) break
                        }
                        homePage.value = cleaned.copy(
                            sections = cleaned.sections.sortedByDescending {
                                sectionInterest(it, familiarArtists)
                            },
                        )
                    }.onFailure {
                        homeFeedError.value = true
                        reportException(it)
                    }
                }

            }

            allLocalItems.value =
                (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
                    .distinctBy { it.id }

            // Fetch listener-made playlists separately from the Home continuation.
            // Failures are isolated and never block the music feed or playback.
            communityPlaylistsJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    loadCommunityPlaylists(
                        homeTasteArtists.toList().take(2),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportRecoverableException("HomeViewModel", "load community playlists", error)
                }
            }

            // MetroList's initial Home only waits for cheap DB data and the primary feed.
            // Recommendations (up to several "related" requests) and Explore are non-critical.
            tasteRecommendationsJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                        forYouSuggestions.value = forYouEngine.getSuggestions(hideExplicit)
                    refreshAllYouTubeItems()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportRecoverableException("HomeViewModel", "load For You suggestions", error)
                }
            }

            exploreJob = viewModelScope.launch(Dispatchers.IO) {
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
                    // Use YouTube Music's dedicated release catalog; Explore is a fallback.
                    val direct = YouTube.newReleaseAlbums().getOrNull()
                        ?.filterExplicit(hideExplicit)
                        ?.distinctBy { it.id }
                        .orEmpty()
                    newReleaseAlbums.value = direct.ifEmpty {
                        explorePage.value?.newReleaseAlbums.orEmpty()
                    }.take(24)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportRecoverableException("HomeViewModel", "load Explore", error)
                }
            }

            refreshAllYouTubeItems()
                    
        } catch (e: Exception) {
            if (homePage.value == null) homeFeedError.value = true
            reportException(e)
        } finally {
            isInitialLoadComplete.value = true
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
    val isLoadingMore = _isLoadingMore.asStateFlow()

    fun loadMoreYouTubeItems(continuation: String?) {
        if (continuation == null || _isLoadingMore.value) return
        // Never hammer a failing/repeated provider token on every scroll pixel.
        if (attemptedContinuations.size >= continuationBudget ||
            !attemptedContinuations.add(continuation)
        ) return
        _isLoadingMore.value = true
        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val next = YouTube.home(continuation = continuation).getOrNull()
                    ?: return@launch
                val current = homePage.value
                // The only feed is the default Home. Preserve its sections and append
                // provider continuations without overwriting newer refresh results.
                if (current?.continuation != continuation) return@launch
                val merged = next.copy(
                    sections = current.sections + next.sections,
                    continuation = next.continuation.takeUnless {
                        it == continuation || attemptedContinuations.size >= continuationBudget
                    },
                )
                homePage.value = cleanYouTubeHomePage(
                    page = merged,
                    hideExplicit = hideExplicit,
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

        // Stay current when playback history changes while Home remains in the
        // navigation back stack. A one-time snapshot left Continue Listening stale.
        viewModelScope.launch(Dispatchers.IO) {
            database.recentlyListenedSongs(limit = 16)
                .distinctUntilChanged()
                .collect { keepListening.value = it }
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
