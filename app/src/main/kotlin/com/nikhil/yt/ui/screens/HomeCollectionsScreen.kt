package com.nikhil.yt.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.pages.HomePage
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.NavigationTitle
import com.nikhil.yt.ui.component.StandardChrome
import com.nikhil.yt.ui.utils.SnapLayoutInfoProvider
import com.nikhil.yt.viewmodels.HomeViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The complete YouTube Music discovery catalog, accessible through one button
 * at the bottom of Home. Shares Home's ViewModel (and HTTP requests) through its
 * navigation back-stack entry instead of creating a second feed on each visit.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeCollectionsScreen(
    navController: NavController,
    viewModel: HomeViewModel,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val suggestions by viewModel.forYouSuggestions.collectAsState()
    val forgotten by viewModel.forgottenFavorites.collectAsState()
    val releases by viewModel.newReleaseAlbums.collectAsState()
    val listenerPlaylists by viewModel.communityPlaylists.collectAsState()
    val home by viewModel.homePage.collectAsState()
    val explore by viewModel.explorePage.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val loadingMore by viewModel.isLoadingMore.collectAsState()
    val feedError by viewModel.homeFeedError.collectAsState()
    LaunchedEffect(viewModel) { viewModel.activateDiscovery() }

    val listState = rememberLazyListState()
    val favoritesState = rememberLazyGridState()
    val horizontalEnabled = remember(listState) {
        derivedStateOf { !listState.isScrollInProgress }
    }
    val hasRelease = home?.sections?.any(::isCollectionsReleaseShelf) == true
    val hasCommunity = home?.sections?.any { section ->
        section.items.isNotEmpty() && section.items.all { it is PlaylistItem } &&
            listOf("community", "listener playlist", "playlists by", "плейлисты пользователей")
                .any { section.title.lowercase().contains(it) }
    } == true

    // Snapshot changes only at the pagination threshold. Do not collect every
    // scroll-pixel change, which used to compete with the main UI thread.
    LaunchedEffect(listState, home?.continuation) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) viewModel.loadMoreYouTubeItems(home?.continuation)
        }
    }

    Column(
        Modifier.fillMaxSize().background(StandardChrome.background),
    ) {
        TopAppBar(
            title = {
                Text(
                    stringResource(R.string.home_collections),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = navController::navigateUp) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            // Native Compose fling eliminates custom velocity jumps; no strong recoil.
            overscrollEffect = null,
        ) {
            suggestions?.takeIf { it.isNotEmpty() }?.let { tracks ->
                item(key = "collections:taste") {
                    TasteRecommendationsSection(
                        suggestions = tracks,
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        navController = navController,
                        playerConnection = playerConnection,
                        menuState = menuState,
                        haptic = haptic,
                        userScrollEnabled = horizontalEnabled.value,
                    )
                }
            }

            forgotten?.takeIf { it.isNotEmpty() }?.let { songs ->
                item(key = "collections:forgotten") {
                    Column {
                        NavigationTitle(title = stringResource(R.string.forgotten_favorites))
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val widthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
                            val snap = remember(favoritesState, widthFactor) {
                                SnapLayoutInfoProvider(
                                    lazyGridState = favoritesState,
                                    positionInLayout = { layoutSize, itemSize ->
                                        (layoutSize * widthFactor / 2f - itemSize / 2f)
                                    },
                                )
                            }
                            ForgottenFavoritesSection(
                                forgottenFavorites = songs,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                horizontalLazyGridItemWidth = maxWidth * widthFactor,
                                lazyGridState = favoritesState,
                                snapLayoutInfoProvider = snap,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                userScrollEnabled = horizontalEnabled.value,
                            )
                        }
                    }
                }
            }

            // Use the actual new-release catalog only when the Home endpoint
            // didn't already provide this category.
            releases.takeIf { it.isNotEmpty() && !hasRelease }?.let { albums ->
                item(key = "collections:releases") {
                    Column {
                        NavigationTitle(
                            title = stringResource(R.string.new_release_albums),
                            onClick = { navController.navigate("new_release") },
                        )
                        HomePageSectionContent(
                            section = remember(albums) {
                                HomePage.Section("", null, null, null, albums)
                            },
                            navController = navController,
                            playerConnection = playerConnection,
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            menuState = menuState,
                            haptic = haptic,
                            scope = scope,
                            userScrollEnabled = horizontalEnabled.value,
                        )
                    }
                }
            }

            listenerPlaylists.takeIf { it.isNotEmpty() && !hasCommunity }?.let { playlists ->
                item(key = "collections:listeners") {
                    CommunityPlaylistsSection(
                        section = remember(playlists) {
                            HomePage.Section("", null, null, null, playlists)
                        }.copy(title = stringResource(R.string.home_community_playlists)),
                        navController = navController,
                        playerConnection = playerConnection,
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        menuState = menuState,
                        haptic = haptic,
                        scope = scope,
                        userScrollEnabled = horizontalEnabled.value,
                    )
                }
            }

            home?.sections
                ?.distinctBy {
                    if (isCollectionsReleaseShelf(it)) "releases"
                    else "${it.endpoint?.browseId}:${it.title}:${it.items.firstOrNull()?.id}"
                }
                ?.forEach { original ->
                    val sectionKey = "collections:yt:${original.endpoint?.browseId}:${original.title}:${original.items.firstOrNull()?.id}"
                    item(key = sectionKey) {
                        val section = if (isCollectionsReleaseShelf(original) && releases.isNotEmpty())
                            original.copy(items = releases)
                        else original
                        if (section.items.all { it is PlaylistItem }) {
                            CommunityPlaylistsSection(
                                section = section,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                scope = scope,
                                userScrollEnabled = horizontalEnabled.value,
                            )
                        } else {
                            Column {
                                HomePageSectionTitle(section, navController)
                                HomePageSectionContent(
                                    section = section,
                                    mediaMetadata = mediaMetadata,
                                    isPlaying = isPlaying,
                                    navController = navController,
                                    playerConnection = playerConnection,
                                    menuState = menuState,
                                    haptic = haptic,
                                    scope = scope,
                                    userScrollEnabled = horizontalEnabled.value,
                                )
                            }
                        }
                    }
                }

            explore?.moodAndGenres?.takeIf { it.isNotEmpty() }?.let { genres ->
                item(key = "collections:genres") {
                    Column {
                        NavigationTitle(
                            title = stringResource(R.string.mood_and_genres),
                            onClick = { navController.navigate("mood_and_genres") },
                        )
                        MoodAndGenresSection(
                            moodAndGenres = genres,
                            navController = navController,
                            userScrollEnabled = horizontalEnabled.value,
                        )
                    }
                }
            }

            if (loading || loadingMore) item(key = "collections:loading") {
                HomeLoadingShimmer()
            }

            if (feedError && home?.sections.isNullOrEmpty() && !loading) {
                item(key = "collections:retry") {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        onClick = viewModel::refresh,
                    ) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

private fun isCollectionsReleaseShelf(section: HomePage.Section): Boolean {
    if (section.endpoint?.browseId == "FEmusic_new_releases_albums") return true
    val name = section.title.lowercase()
    return listOf("new release", "latest release", "новые релизы", "новинки")
        .any(name::contains)
}
