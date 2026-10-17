/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.screens

import com.nikhil.yt.ui.utils.liveSavedStateHandle
import com.nikhil.yt.ui.component.StandardChrome
import androidx.compose.foundation.background
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
    val headerAccountName by viewModel.accountName.collectAsState()
    val headerAccountImage by viewModel.accountImageUrl.collectAsState()
    val haptic = LocalHapticFeedback.current

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val quickPicks by viewModel.quickPicks.collectAsState()
    val forgottenFavorites by viewModel.forgottenFavorites.collectAsState()
    val keepListening by viewModel.keepListening.collectAsState()
    val homePage by viewModel.homePage.collectAsState()
    val explorePage by viewModel.explorePage.collectAsState()
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
                if (frame.lastVisible != null &&
                    frame.totalItems > 0 &&
                    frame.lastVisible >= frame.totalItems - 3
                ) {
                    viewModel.loadMoreYouTubeItems(homePage?.continuation)
                }
            } else {
                previousIndex = -1
            }
        }
    }
    // Database emissions must not forcibly reset the nested favorites grid.


    val tonalStart = MaterialTheme.colorScheme.primaryContainer
    val tonalMiddle = MaterialTheme.colorScheme.secondaryContainer

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(StandardChrome.background),
    ) {
        /*
         * Match ArchiveTune's restrained Home backdrop: one short tonal wash that dissolves into
         * the neutral page. The previous five radial glows made Home look busier and also kept a
         * much larger draw area alive. Quick Picks and every section below are deliberately left
         * untouched.
         */
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(430.dp)
                    .align(Alignment.TopCenter)
                    .drawWithCache {
                        val brush =
                            Brush.verticalGradient(
                                0f to tonalStart.copy(alpha = 0.30f),
                                0.42f to tonalMiddle.copy(alpha = 0.14f),
                                1f to Color.Transparent,
                            )
                        onDrawBehind { drawRect(brush) }
                    },
        )
        
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

            // MainActivity no longer draws a second, floating Home app bar. The
            // shared insets still reserve its old height for other destinations;
            // subtract that reserve here and give the scrollable header that slot.
            val oldContentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
            val direction = LocalLayoutDirection.current
            val homeContentPadding = PaddingValues(
                start = oldContentPadding.calculateStartPadding(direction),
                top = (oldContentPadding.calculateTopPadding() - AppBarHeight).coerceAtLeast(0.dp),
                end = oldContentPadding.calculateEndPadding(direction),
                bottom = oldContentPadding.calculateBottomPadding(),
            )
            LazyColumn(
                state = lazylistState,
                contentPadding = homeContentPadding,
            ) {
                item(key = "home:header", contentType = "home-header") {
                    TopAppBar(
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        title = {
                            StandardHeaderTitle(
                                accountName = headerAccountName,
                                accountImageUrl = headerAccountImage,
                                onAccountClick = { navController.navigate("settings/account") },
                                showHomeGreeting = true,
                            )
                        },
                        actions = {
                            IconButton(onClick = { navController.navigate("settings") }) {
                                Icon(
                                    painter = painterResource(R.drawable.settings),
                                    contentDescription = stringResource(R.string.settings),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
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
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        navController = navController,
                        playerConnection = playerConnection,
                        menuState = menuState,
                        haptic = haptic
                    )
                }
            }


            quickPicks?.takeIf { it.isNotEmpty() }?.let { picks ->
                item(key = "home:quick-list") {
                    QuickPicksListSection(
                        quickPicks = picks,
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

            keepListening?.takeIf { it.isNotEmpty() }?.let { songs ->
                item(key = "home:keep-listening") {
                    Column {
                        NavigationTitle(title = stringResource(R.string.keep_listening))
                        KeepListeningSection(
                            keepListening = songs,
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

            forgottenFavorites?.takeIf { it.isNotEmpty() }?.let { favorites ->
                item(key = "home:forgotten") {
                    Column {
                        NavigationTitle(title = stringResource(R.string.forgotten_favorites))
                        ForgottenFavoritesSection(
                            forgottenFavorites = favorites,
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

            forYouSuggestions?.takeIf { it.isNotEmpty() }?.let { suggestions ->
                item(key = "home:taste") {
                    TasteRecommendationsSection(
                        suggestions = suggestions,
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

            homePage?.sections?.forEach { section ->
                val sectionKey =
                    "home:yt:${section.endpoint?.browseId.orEmpty()}:${section.title}:${section.items.firstOrNull()?.id.orEmpty()}"
                val isPlaylistCarousel =
                    section.items.isNotEmpty() &&
                        section.items.all { it is com.nikhil.yt.innertube.models.PlaylistItem }

                item(key = sectionKey) {
                    if (isPlaylistCarousel) {
                        CommunityPlaylistsSection(
                            section = section,
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
