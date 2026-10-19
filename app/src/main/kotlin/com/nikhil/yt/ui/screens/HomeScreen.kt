/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.screens

import com.nikhil.yt.ui.utils.liveSavedStateHandle
import com.nikhil.yt.ui.component.StandardChrome
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.pages.HomePage
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.AppBarHeight
import com.nikhil.yt.ui.component.StandardHeaderTitle
import com.nikhil.yt.db.entities.Album
import com.nikhil.yt.db.entities.Artist
import com.nikhil.yt.db.entities.Playlist
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.playback.queues.LocalAlbumRadio
import com.nikhil.yt.playback.queues.YouTubeAlbumRadio
import com.nikhil.yt.playback.queues.YouTubeQueue
import com.nikhil.yt.ui.component.ScrollActionButton
import com.nikhil.yt.ui.component.LocalBottomSheetPageState
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.NavigationTitle
import com.nikhil.yt.ui.utils.SnapLayoutInfoProvider
import com.nikhil.yt.viewmodels.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random


/** Recognize the release shelf returned by the same YouTube Music Home feed. */
private fun isYouTubeReleaseShelf(section: HomePage.Section): Boolean {
    if (section.endpoint?.browseId == "FEmusic_new_releases_albums") return true
    val title = section.title.trim().lowercase()
    return listOf(
        "new release", "latest release", "new albums", "recent releases",
        "новые релизы", "новинки", "свежие релизы", "нові релізи",
        "nuevos lanzamientos", "nouvelles sorties", "neue veröffentlichungen",
    ).any(title::contains)
}

/** Immutable scroll sample, collected outside Compose's measure/draw passes. */
private data class HomeScrollSnapshot(
    val scrolling: Boolean,
    val index: Int,
    val offset: Int,
    val lastVisible: Int?,
    val totalItems: Int,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val quickPicks by viewModel.quickPicks.collectAsState()
    val forgottenFavorites by viewModel.forgottenFavorites.collectAsState()
    val keepListening by viewModel.keepListening.collectAsState()
    val homePage by viewModel.homePage.collectAsState()
    val explorePage by viewModel.explorePage.collectAsState()
    val newReleaseAlbums by viewModel.newReleaseAlbums.collectAsState()
    val providerHasReleaseShelf = homePage?.sections?.any(::isYouTubeReleaseShelf) == true
    val forYouSuggestions by viewModel.forYouSuggestions.collectAsState()

    val allLocalItems by viewModel.allLocalItems.collectAsState()
    val allYtItems by viewModel.allYtItems.collectAsState()

    val isLoading: Boolean by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val homeFeedError by viewModel.homeFeedError.collectAsState()
    val isMoodAndGenresLoading = isLoading && explorePage?.moodAndGenres == null
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    val forgottenFavoritesLazyGridState = rememberLazyGridState()

    val scope = rememberCoroutineScope()
    val lazylistState = rememberLazyListState()
    // During vertical motion, sideways drags must not turn the recommendation carousels.
    // Their ordinary horizontal gestures remain available when the feed is idle.
    val horizontalShelvesEnabled = !lazylistState.isScrollInProgress
    var scrollingUp by remember(lazylistState) { mutableStateOf(true) }
    // This screen's own entry: it cannot be destroyed while this composition is alive,
    // and observing it does not recompose the screen on unrelated navigation.
    val backStackEntry = LocalNavBackStackEntry.current
    val scrollToTop =
        backStackEntry?.liveSavedStateHandle()?.getStateFlow("scrollToTop", false)?.collectAsState()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            lazylistState.animateScrollToItem(0)
            backStackEntry?.liveSavedStateHandle()?.set("scrollToTop", false)
        }
    }

    LaunchedEffect(lazylistState, homePage?.continuation) {
        var previousIndex = -1
        var previousOffset = 0
        // One event-driven scroll observer serves pagination and the floating button.
        // Unlike ScrollUtils.isScrollingUp(), this never writes inside derivedStateOf.
        snapshotFlow {
            HomeScrollSnapshot(
                scrolling = lazylistState.isScrollInProgress,
                index = lazylistState.firstVisibleItemIndex,
                offset = lazylistState.firstVisibleItemScrollOffset,
                lastVisible = lazylistState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                totalItems = lazylistState.layoutInfo.totalItemsCount,
            )
        }.collect { frame ->
            if (frame.scrolling) {
                if (previousIndex >= 0) {
                    val movingUp = when {
                        frame.index < previousIndex -> true
                        frame.index > previousIndex -> false
                        frame.offset < previousOffset -> true
                        frame.offset > previousOffset -> false
                        else -> scrollingUp
                    }
                    if (scrollingUp != movingUp) scrollingUp = movingUp
                }
                previousIndex = frame.index
                previousOffset = frame.offset
            } else {
                previousIndex = -1
            }
            // A short feed might already be at the end without any scroll
            // gesture. Its continuation must still be eligible for loading.
            if (frame.lastVisible != null &&
                frame.totalItems > 0 &&
                frame.lastVisible >= frame.totalItems - 3
            ) {
                viewModel.loadMoreYouTubeItems(homePage?.continuation)
            }
        }
    }
    // Database emissions must not forcibly reset the nested favorites grid.


    // One solid page surface. The former short, three-stop overlay ended after
    // 430dp and produced visible horizontal bands while scrolling the feed.
    val nativeFling = ScrollableDefaults.flingBehavior()
    val homeFling = remember(nativeFling) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                with(nativeFling) { performFling(initialVelocity * 0.84f) }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(StandardChrome.background),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pullToRefresh(
                    state = pullRefreshState,
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refresh
                )
        ) {
            val horizontalLazyGridItemWidthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
            val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor
            val forgottenFavoritesSnapLayoutInfoProvider = remember(forgottenFavoritesLazyGridState, horizontalLazyGridItemWidthFactor) {
                SnapLayoutInfoProvider(
                    lazyGridState = forgottenFavoritesLazyGridState,
                    positionInLayout = { layoutSize, itemSize ->
                        (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                    }
                )
            }

            // One natural scroll surface. Greeting and controls are simply
            // the first row of the feed: no independent element/animation.
            LazyColumn(
                state = lazylistState,
                contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                flingBehavior = homeFling,
                // No abrupt edge stretching/recoil; the list settles at its real bounds.
                overscrollEffect = null,
            ) {

                    quickPicks?.takeIf { it.isNotEmpty() }?.let { picks ->
            /*
                item {
                    NavigationTitle(
                        title = stringResource(R.string.quick_picks),
                        modifier = Modifier
                    )
                }
            */

                item(key = "home:quick-hero") {
                    QuickPicksSection(
                        quickPicks = picks,
                        userScrollEnabled = horizontalShelvesEnabled,
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        navController = navController,
                        playerConnection = playerConnection,
                        menuState = menuState,
                        haptic = haptic
                    )
                }
            }


            keepListening?.takeIf { it.isNotEmpty() }?.let { songs ->
                item(key = "home:keep-listening") {
                    Column {
                        NavigationTitle(title = stringResource(R.string.keep_listening))
                        KeepListeningSection(
                            keepListening = songs,
                            userScrollEnabled = horizontalShelvesEnabled,
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            navController = navController,
                            playerConnection = playerConnection,
                            menuState = menuState,
                            haptic = haptic,
                        )
                    }
                }
            }

            forYouSuggestions?.takeIf { it.isNotEmpty() }?.let { suggestions ->
                item(key = "home:taste") {
                    TasteRecommendationsSection(
                        suggestions = suggestions,
                        userScrollEnabled = horizontalShelvesEnabled,
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        navController = navController,
                        playerConnection = playerConnection,
                        menuState = menuState,
                        haptic = haptic,
                        modifier = Modifier
                    )
                }
            }


            // This is the current YouTube Music release catalog, not a static
            // local playlist or a rebranded recommendation shelf.
            // Only add a release section when YouTube's Home feed has none.
            // Otherwise refresh the existing section in place to avoid a
            // duplicated carousel and a mid-scroll insert/remove jump.
            newReleaseAlbums.takeIf { it.isNotEmpty() && !providerHasReleaseShelf }?.let { albums ->
                item(key = "home:youtube-new-release-albums") {
                    val releases = remember(albums) {
                        HomePage.Section(
                            title = "",
                            label = null,
                            thumbnail = null,
                            endpoint = null,
                            items = albums,
                        )
                    }
                    Column {
                        NavigationTitle(
                            title = stringResource(R.string.new_release_albums),
                            onClick = { navController.navigate("new_release") },
                        )
                        HomePageSectionContent(
                            section = releases,
                            userScrollEnabled = horizontalShelvesEnabled,
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            navController = navController,
                            playerConnection = playerConnection,
                            menuState = menuState,
                            haptic = haptic,
                            scope = scope,
                        )
                    }
                }
            }

            forgottenFavorites?.takeIf { it.isNotEmpty() }?.let { favorites ->
                item(key = "home:forgotten") {
                    Column {
                        NavigationTitle(title = stringResource(R.string.forgotten_favorites))
                        ForgottenFavoritesSection(
                            forgottenFavorites = favorites,
                            userScrollEnabled = horizontalShelvesEnabled,
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            horizontalLazyGridItemWidth = horizontalLazyGridItemWidth,
                            lazyGridState = forgottenFavoritesLazyGridState,
                            snapLayoutInfoProvider = forgottenFavoritesSnapLayoutInfoProvider,
                            navController = navController,
                            playerConnection = playerConnection,
                            menuState = menuState,
                            haptic = haptic,
                        )
                    }
                }
            }

            homePage?.sections
                // If the provider repeats the release shelf, keep its first position.
                ?.distinctBy { if (isYouTubeReleaseShelf(it)) "home:yt:releases" else
                    "home:yt:${it.endpoint?.browseId}:${it.title}:${it.items.firstOrNull()?.id}" }
                ?.forEach { providerSection ->
                // Fresh YouTube Music album results replace stale albums in situ:
                // no duplicate New Releases shelf and no unnecessary feed reflow.
                val section = if (isYouTubeReleaseShelf(providerSection) && newReleaseAlbums.isNotEmpty()) {
                    providerSection.copy(items = newReleaseAlbums)
                } else providerSection
                val sectionKey =
                    "home:yt:${providerSection.endpoint?.browseId.orEmpty()}:${providerSection.title}:${providerSection.items.firstOrNull()?.id.orEmpty()}"
                val isPlaylistCarousel =
                    section.items.isNotEmpty() &&
                        section.items.all { it is com.nikhil.yt.innertube.models.PlaylistItem }

                item(key = sectionKey) {
                    if (isPlaylistCarousel) {
                        CommunityPlaylistsSection(
                            section = section,
                            userScrollEnabled = horizontalShelvesEnabled,
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            navController = navController,
                            playerConnection = playerConnection,
                            menuState = menuState,
                            haptic = haptic,
                            scope = scope,
                        )
                    } else {
                        Column {
                            HomePageSectionTitle(
                                section = section,
                                navController = navController,
                            )
                            HomePageSectionContent(
                                section = section,
                                userScrollEnabled = horizontalShelvesEnabled,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                scope = scope,
                            )
                        }
                    }
                }
            }

            if (homeFeedError && homePage?.sections.isNullOrEmpty() && !isLoading) {
                item(key = "home:feed-error") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.error_network_problem_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::refresh) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }

            if (
                isLoading || isLoadingMore
            ) {
                item(key = "home:loading") {
                    HomeLoadingShimmer()
                }
            }

                explorePage?.moodAndGenres?.let { genres ->
                    item(key = "home:genres") {
                        Column {
                            NavigationTitle(
                                title = stringResource(R.string.mood_and_genres),
                                onClick = { navController.navigate("mood_and_genres") },
                            )
                            MoodAndGenresSection(
                                moodAndGenres = genres,
                                userScrollEnabled = horizontalShelvesEnabled,
                                navController = navController,
                            )
                        }
                    }
                }

                if (isMoodAndGenresLoading) {
                    item(key = "home:genre-loading") {
                        MoodAndGenresLoadingShimmer()
                    }
                }
            }

            ScrollActionButton(
                visible = (allLocalItems.isNotEmpty() || allYtItems.isNotEmpty()) && scrollingUp,
                icon = R.drawable.shuffle,
                onClick = {
                    val local = when {
                        allLocalItems.isNotEmpty() && allYtItems.isNotEmpty() ->
                            Random.nextFloat() < 0.5f
                        allLocalItems.isNotEmpty() -> true
                        else -> false
                    }
                    scope.launch(Dispatchers.Main) {
                        if (local) {
                            when (val luckyItem = allLocalItems.random()) {
                                is Song -> playerConnection.playQueue(YouTubeQueue.radio(luckyItem.toMediaMetadata()))
                                is Album -> {
                                    val albumWithSongs = withContext(Dispatchers.IO) {
                                        database.albumWithSongs(luckyItem.id).first()
                                    }
                                    albumWithSongs?.let {
                                        playerConnection.playQueue(LocalAlbumRadio(it))
                                    }
                                }
                                is Artist -> {}
                                is Playlist -> {}
                            }
                        } else {
                            when (val luckyItem = allYtItems.random()) {
                                is SongItem -> playerConnection.playQueue(YouTubeQueue.radio(luckyItem.toMediaMetadata()))
                                is AlbumItem -> playerConnection.playQueue(YouTubeAlbumRadio(luckyItem.playlistId))
                                is ArtistItem -> luckyItem.radioEndpoint?.let {
                                    playerConnection.playQueue(YouTubeQueue(it))
                                }
                                is PlaylistItem -> luckyItem.playEndpoint?.let {
                                    playerConnection.playQueue(YouTubeQueue(it))
                                }
                            }
                        }
                    }
                }
            )

            Indicator(
                isRefreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
            )
        }
    }
}
