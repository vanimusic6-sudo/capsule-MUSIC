/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.screens

import com.nikhil.yt.ui.component.StandardHomeChips
import com.nikhil.yt.ui.utils.liveSavedStateHandle
import com.nikhil.yt.ui.component.StandardChrome
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.utils.parseCookieString
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.InnerTubeCookieKey
import com.nikhil.yt.db.entities.Album
import com.nikhil.yt.db.entities.Artist
import com.nikhil.yt.db.entities.Playlist
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.playback.queues.LocalAlbumRadio
import com.nikhil.yt.playback.queues.YouTubeAlbumRadio
import com.nikhil.yt.playback.queues.YouTubeQueue
import com.nikhil.yt.ui.component.HideOnScrollFAB
import com.nikhil.yt.ui.component.LocalBottomSheetPageState
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.NavigationTitle
import com.nikhil.yt.ui.utils.SnapLayoutInfoProvider
import com.nikhil.yt.utils.rememberPreference
import com.nikhil.yt.viewmodels.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random


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
    val forYouSuggestions by viewModel.forYouSuggestions.collectAsState()

    val allLocalItems by viewModel.allLocalItems.collectAsState()
    val allYtItems by viewModel.allYtItems.collectAsState()
    val selectedChip by viewModel.selectedChip.collectAsState()

    val isLoading: Boolean by viewModel.isLoading.collectAsState()
    val isMoodAndGenresLoading = isLoading && explorePage?.moodAndGenres == null
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    val forgottenFavoritesLazyGridState = rememberLazyGridState()

    val accountName by viewModel.accountName.collectAsState()
    val accountImageUrl by viewModel.accountImageUrl.collectAsState()
    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val (showHomeCategoryChips) = rememberPreference(ShowHomeCategoryChipsKey, true)
    val capsuleDock by rememberPreference(CapsuleBottomBarEnabledKey, false)
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    val url = if (isLoggedIn) accountImageUrl else null

    val scope = rememberCoroutineScope()
    val lazylistState = rememberLazyListState()
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

    LaunchedEffect(Unit) {
        snapshotFlow { lazylistState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                val len = lazylistState.layoutInfo.totalItemsCount
                if (lastVisibleIndex != null && lastVisibleIndex >= len - 3) {
                    viewModel.loadMoreYouTubeItems(homePage?.continuation)
                }
            }
    }

    if (selectedChip != null) {
        BackHandler {
            viewModel.toggleChip(selectedChip)
        }
    }

    LaunchedEffect(showHomeCategoryChips, selectedChip) {
        if (!showHomeCategoryChips && selectedChip != null) {
            viewModel.toggleChip(selectedChip)
        }
    }

    LaunchedEffect(forgottenFavorites) {
        forgottenFavoritesLazyGridState.scrollToItem(0)
    }


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
            val forgottenFavoritesSnapLayoutInfoProvider = remember(forgottenFavoritesLazyGridState) {
                SnapLayoutInfoProvider(
                    lazyGridState = forgottenFavoritesLazyGridState,
                    positionInLayout = { layoutSize, itemSize ->
                        (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                    }
                )
            }

            LazyColumn(
                state = lazylistState,
                contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
            ) {
                if (showHomeCategoryChips && homePage?.chips?.isNotEmpty() == true) {
                    item {
                        if (capsuleDock) {
                            ChipsRow(
                                chips = homePage?.chips.orEmpty().map { it to it.title },
                                currentValue = selectedChip,
                                onValueUpdate = viewModel::toggleChip,
                            )
                        } else {
                            StandardHomeChips(
                                chips = homePage?.chips.orEmpty().map { it to it.title },
                                currentValue = selectedChip,
                                onValueUpdate = viewModel::toggleChip,
                            )
                        }
                    }
                }

                quickPicks?.takeIf { it.isNotEmpty() }?.let { picks ->
            /*
                item {
                    NavigationTitle(
                        title = stringResource(R.string.quick_picks),
                        modifier = Modifier.animateItem()
                    )
                }
            */

                item {
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
                item {
                    QuickPicksListSection(
                        quickPicks = picks,
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        navController = navController,
                        playerConnection = playerConnection,
                        menuState = menuState,
                        haptic = haptic,
                        modifier = Modifier.animateItem()
                    )
                }
            }

            keepListening?.takeIf { it.isNotEmpty() }?.let { items ->
                item {
                    NavigationTitle(
                        title = stringResource(R.string.keep_listening),
                        modifier = Modifier.animateItem()
                    )
                }

                item {
                    KeepListeningSection(
                        keepListening = items,
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        navController = navController,
                        playerConnection = playerConnection,
                        menuState = menuState,
                        haptic = haptic,
                        scope = scope
                    )
                }
            }

            AccountPlaylistsContainer(
                viewModel = viewModel,
                accountName = accountName,
                accountImageUrl = url,
                mediaMetadata = mediaMetadata,
                isPlaying = isPlaying,
                navController = navController,
                playerConnection = playerConnection,
                menuState = menuState,
                haptic = haptic,
                scope = scope
            )

            forgottenFavorites?.takeIf { it.isNotEmpty() }?.let { favorites ->
                item {
                    NavigationTitle(
                        title = stringResource(R.string.forgotten_favorites),
                        modifier = Modifier.animateItem()
                    )
                }

                item {
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
                        haptic = haptic
                    )
                }
            }

            forYouSuggestions?.takeIf { it.isNotEmpty() }?.let { suggestions ->
                item {
                    TasteRecommendationsSection(
                        suggestions = suggestions,
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        navController = navController,
                        playerConnection = playerConnection,
                        menuState = menuState,
                        haptic = haptic,
                        modifier = Modifier.animateItem()
                    )
                }
            }

            homePage?.sections?.forEach { section ->
                val isCommunity = section.title?.contains("community", ignoreCase = true) == true ||
                    section.title?.contains("From the", ignoreCase = true) == true ||
                    section.title?.contains("Trending", ignoreCase = true) == true &&
                    section.items.all { it is com.nikhil.yt.innertube.models.PlaylistItem }

                if (isCommunity) {
                    item {
                        CommunityPlaylistsSection(
                            section = section,
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            navController = navController,
                            playerConnection = playerConnection,
                            menuState = menuState,
                            haptic = haptic,
                            scope = scope,
                            modifier = Modifier.animateItem()
                        )
                    }
                } else {
                    item {
                        HomePageSectionTitle(
                            section = section,
                            navController = navController,
                            modifier = Modifier.animateItem()
                        )
                    }

                    item {
                        HomePageSectionContent(
                            section = section,
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            navController = navController,
                            playerConnection = playerConnection,
                            menuState = menuState,
                            haptic = haptic,
                            scope = scope
                        )
                    }
                }
            }

            if (isLoading || homePage?.continuation != null && homePage?.sections?.isNotEmpty() == true) {
                item {
                    HomeLoadingShimmer(modifier = Modifier.animateItem())
                }
            }

            explorePage?.moodAndGenres?.let { genres ->
                item {
                    NavigationTitle(
                        title = stringResource(R.string.mood_and_genres),
                        onClick = { navController.navigate("mood_and_genres") },
                        modifier = Modifier.animateItem()
                    )
                }
                item {
                    MoodAndGenresSection(
                        moodAndGenres = genres,
                        navController = navController
                    )
                }
            }

            if (isMoodAndGenresLoading) {
                item {
                    MoodAndGenresLoadingShimmer(modifier = Modifier.animateItem())
                }
            }
            }

            HideOnScrollFAB(
                visible = allLocalItems.isNotEmpty() || allYtItems.isNotEmpty(),
                lazyListState = lazylistState,
                icon = R.drawable.shuffle,
                onClick = {
                    val local = when {
                        allLocalItems.isNotEmpty() && allYtItems.isNotEmpty() -> Random.nextFloat() < 0.5
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
