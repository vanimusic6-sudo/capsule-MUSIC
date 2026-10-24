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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.nikhil.yt.innertube.pages.HomePage
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.NavigationTitle
import com.nikhil.yt.ui.utils.SnapLayoutInfoProvider
import com.nikhil.yt.viewmodels.HomeViewModel
import com.nikhil.yt.constants.ExperimentalSourcesEnabledKey
import com.nikhil.yt.constants.HomeFeedSourcesKey
import com.nikhil.yt.discovery.HomeFeedSource
import com.nikhil.yt.discovery.ExperimentalCatalog
import com.nikhil.yt.discovery.ExperimentalRanking
import com.nikhil.yt.utils.rememberPreference


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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val quickPicks by viewModel.quickPicks.collectAsState()
    val forgottenFavorites by viewModel.forgottenFavorites.collectAsState()
    val keepListening by viewModel.keepListening.collectAsState()
    val homePage by viewModel.homePage.collectAsState()
    val newReleaseAlbums by viewModel.newReleaseAlbums.collectAsState()
    val providerHasReleaseShelf = homePage?.sections?.any(::isYouTubeReleaseShelf) == true
    val forYouSuggestions by viewModel.forYouSuggestions.collectAsState()
    val (experimentalSourcesEnabled, _) = rememberPreference(ExperimentalSourcesEnabledKey, false)
    val (homeSourceSetting, _) = rememberPreference(HomeFeedSourcesKey, HomeFeedSource.DEFAULT)
    val homeSources = remember(homeSourceSetting) { HomeFeedSource.parse(homeSourceSetting) }
    // A disabled experimental catalog never strands Home without content.
    val includeYouTube = !experimentalSourcesEnabled || HomeFeedSource.YOUTUBE in homeSources
    val rareSources = if (experimentalSourcesEnabled) homeSources - HomeFeedSource.YOUTUBE
        else emptySet()
    val tastes = remember(forYouSuggestions) {
        forYouSuggestions.orEmpty().mapNotNull { it.artists.firstOrNull()?.name }
            .distinct().take(2)
    }
    val catalogHome = rememberCatalogHomeState(rareSources, tastes)
    // Do not silently substitute YouTube when the user deliberately chose an
    // external-only Home. Show a retry state for an unavailable source instead.
    val displayYouTube = includeYouTube
    // A provider batch publishes once: partial arrivals must not reshuffle a visible LazyRow.
    val beyond = catalogHome.discoverySnapshot
    val listenerPlaylists by viewModel.communityPlaylists.collectAsState()


    val isLoading: Boolean by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val homeFeedError by viewModel.homeFeedError.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    val forgottenFavoritesLazyGridState = rememberLazyGridState()

    val scope = rememberCoroutineScope()
    val lazylistState = rememberLazyListState()
    // During vertical motion, sideways drags must not turn the recommendation carousels.
    // Their ordinary horizontal gestures remain available when the feed is idle.
    val horizontalShelvesEnabled = remember(lazylistState) {
        derivedStateOf { !lazylistState.isScrollInProgress }
    }
    // This screen's own entry: it cannot be destroyed while this composition is alive,
    // and observing it does not recompose the screen on unrelated navigation.
    val backStackEntry = LocalNavBackStackEntry.current
    val scrollToTop =
        backStackEntry?.liveSavedStateHandle()?.getStateFlow("scrollToTop", false)?.collectAsState()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            // Consume navigation request before any suspend point. An old
            // reselect must not hijack an in-progress finger fling.
            backStackEntry?.liveSavedStateHandle()?.set("scrollToTop", false)
            if (!lazylistState.isScrollInProgress) lazylistState.scrollToItem(0)
        }
    }

    // Paginate on the edge transition, never on every scroll pixel.
    LaunchedEffect(lazylistState, homePage?.continuation, displayYouTube) {
        if (!displayYouTube) return@LaunchedEffect
        snapshotFlow {
            val info = lazylistState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearBottom ->
            if (nearBottom) viewModel.loadMoreYouTubeItems(homePage?.continuation)
        }
    }
    // Database emissions must not forcibly reset the nested favorites grid.


    // One solid page surface. The former short, three-stop overlay ended after
    // 430dp and produced visible horizontal bands while scrolling the feed.
    Box(
        modifier = Modifier.fillMaxSize().background(StandardChrome.background),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pullToRefresh(
                    state = pullRefreshState,
                    isRefreshing = if (includeYouTube) isRefreshing else catalogHome.loading,
                    onRefresh = {
                        if (includeYouTube) viewModel.refresh()
                        if (rareSources.isNotEmpty()) catalogHome.refresh()
                    }
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
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                // No abrupt edge stretching/recoil; the list settles at its real bounds.
                overscrollEffect = null,
            ) {

                    quickPicks?.takeIf { displayYouTube && it.isNotEmpty() }?.let { picks ->
                item(key = "home:quick-hero") {
                    QuickPicksSection(
                        quickPicks = picks,
                        userScrollEnabled = horizontalShelvesEnabled.value,
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        navController = navController,
                        playerConnection = playerConnection,
                        menuState = menuState,
                        haptic = haptic
                    )
                }
            }


            keepListening?.takeIf { displayYouTube && it.isNotEmpty() }?.let { songs ->
                item(key = "home:keep-listening") {
                    Column {
                        NavigationTitle(title = stringResource(R.string.keep_listening))
                        KeepListeningSection(
                            keepListening = songs,
                            userScrollEnabled = horizontalShelvesEnabled.value,
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

            forYouSuggestions?.takeIf { displayYouTube && it.isNotEmpty() }?.let { suggestions ->
                item(key = "home:taste") {
                    TasteRecommendationsSection(
                        suggestions = suggestions,
                        userScrollEnabled = horizontalShelvesEnabled.value,
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


            // External sources are true Home providers. Render multiple
            // independent catalog rows rather than one optional music shelf.
            if (rareSources.isNotEmpty()) {
                if (beyond.isNotEmpty()) item(key = "home:catalog-beyond") {
                    CatalogHomeRow(
                        title = stringResource(R.string.capsule_discovery_home_title),
                        source = null,
                        tracks = beyond,
                        userScrollEnabled = horizontalShelvesEnabled.value,
                        playerConnection = playerConnection,
                        navController = navController,
                    )
                }
                catalogHome.lanes.forEach { lane ->
                    item(key = "home:catalog:" + lane.source.id + ":" + lane.kind.name + ":" + (lane.titleRes ?: 0)) {
                        CatalogHomeRow(
                            title = lane.source.label + " · " + (lane.titleRes?.let { stringResource(it) } ?: catalogLaneTitle(lane.kind)),
                            source = lane.source,
                            tracks = lane.tracks,
                            userScrollEnabled = horizontalShelvesEnabled.value,
                            playerConnection = playerConnection,
                            navController = navController,
                        )
                    }
                }
                if (catalogHome.loading && catalogHome.lanes.isEmpty()) {
                    item(key = "home:catalog-loading") { HomeLoadingShimmer() }
                }
                if (catalogHome.finished && catalogHome.lanes.isEmpty() &&
                    rareSources.isNotEmpty()) {
                    item(key = "home:catalog-unavailable") {
                        CatalogHomeError(onRetry = catalogHome::refresh)
                    }
                }
                // Partial provider errors do not interrupt healthy shelves.

            }

            // This is the current YouTube Music release catalog, not a static
            // local playlist or a rebranded recommendation shelf.
            // Only add a release section when YouTube's Home feed has none.
            // Otherwise refresh the existing section in place to avoid a
            // duplicated carousel and a mid-scroll insert/remove jump.
            newReleaseAlbums.takeIf { displayYouTube && it.isNotEmpty() && !providerHasReleaseShelf }?.let { albums ->
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
                            userScrollEnabled = horizontalShelvesEnabled.value,
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

            forgottenFavorites?.takeIf { displayYouTube && it.isNotEmpty() }?.let { favorites ->
                item(key = "home:forgotten") {
                    Column {
                        NavigationTitle(title = stringResource(R.string.forgotten_favorites))
                        ForgottenFavoritesSection(
                            forgottenFavorites = favorites,
                            userScrollEnabled = horizontalShelvesEnabled.value,
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

            // Discover genuine user-created YouTube Music playlists in the feed.
            listenerPlaylists.takeIf { displayYouTube && it.isNotEmpty() }?.let { playlists ->
                item(key = "home:listener-community-playlists") {
                    CommunityPlaylistsSection(
                        section = remember(playlists) {
                            HomePage.Section(
                                title = "", label = null, thumbnail = null,
                                endpoint = null, items = playlists,
                            )
                        }.copy(title = stringResource(R.string.home_community_playlists)),
                        userScrollEnabled = horizontalShelvesEnabled.value,
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

            homePage?.sections?.takeIf { displayYouTube }
                // If the provider repeats the release shelf, keep its first position.
                ?.distinctBy { if (isYouTubeReleaseShelf(it)) "home:yt:releases" else
                    "home:yt:${it.endpoint?.browseId}:${it.endpoint?.params}:${it.title}" }
                ?.forEach { providerSection ->
                // Fresh YouTube Music album results replace stale albums in situ:
                // no duplicate New Releases shelf and no unnecessary feed reflow.
                val section = if (isYouTubeReleaseShelf(providerSection) && newReleaseAlbums.isNotEmpty()) {
                    providerSection.copy(items = newReleaseAlbums)
                } else providerSection
                val sectionKey =
                    "home:yt:${providerSection.endpoint?.browseId.orEmpty()}:${providerSection.endpoint?.params}:${providerSection.title}"
                val isPlaylistCarousel =
                    section.items.isNotEmpty() &&
                        section.items.all { it is com.nikhil.yt.innertube.models.PlaylistItem }

                item(key = sectionKey) {
                    if (isPlaylistCarousel) {
                        CommunityPlaylistsSection(
                            section = section,
                            userScrollEnabled = horizontalShelvesEnabled.value,
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
                                userScrollEnabled = horizontalShelvesEnabled.value,
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

            if (includeYouTube && homeFeedError && homePage?.sections.isNullOrEmpty() && !isLoading) {
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
                displayYouTube && (isLoading || isLoadingMore)
            ) {
                item(key = "home:loading") {
                    HomeLoadingShimmer()
                }
            }


                // "Подборки" means situations (road, sleep, party etc).
                // Music shelves stay on the Home page.
                if (displayYouTube) item(key = "home:collections-button") {
                    // A Capsule card, not the oversized Material pill. Padding
                    // below the card lifts it clear of the mini-player, while
                    // the existing player-aware list inset remains untouched.
                    Surface(
                        onClick = { navController.navigate("mood_and_genres") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp)
                            .height(76.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = StandardChrome.panel,
                        contentColor = StandardChrome.text,
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.home_collections),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StandardChrome.text,
                                )
                                Text(
                                    text = stringResource(R.string.home_collections_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StandardChrome.muted,
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(StandardChrome.selected),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.arrow_forward),
                                    contentDescription = null,
                                    tint = StandardChrome.text,
                                    modifier = Modifier.size(21.dp),
                                )
                            }
                        }
                    }
                }
            }

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
