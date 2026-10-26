package com.nikhil.yt.ui.screens.search

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.AppBarHeight
import com.nikhil.yt.constants.ExperimentalSourcesEnabledKey
import com.nikhil.yt.constants.SearchFilterHeight
import com.nikhil.yt.discovery.ExperimentalArtistRoute
import com.nikhil.yt.discovery.ExperimentalCatalog
import com.nikhil.yt.discovery.ExperimentalTrackQueue
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import com.nikhil.yt.innertube.models.*
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.playback.queues.YouTubeQueue
import com.nikhil.yt.ui.component.*
import com.nikhil.yt.ui.component.shimmer.ListItemPlaceHolder
import com.nikhil.yt.ui.component.shimmer.ShimmerHost
import com.nikhil.yt.ui.menu.*
import com.nikhil.yt.utils.rememberPreference
import com.nikhil.yt.viewmodels.OnlineSearchViewModel
import kotlinx.coroutines.launch

private enum class SearchChip {
    ALL, SONGS, VIDEOS, ALBUMS, ARTISTS, COMMUNITY_PLAYLISTS, FEATURED_PLAYLISTS,
    AUDIUS, OPENVERSE, ARCHIVE, CCMIXTER, RADIO,
}

private enum class RareFilter { ALL, SHORT, LONG, ELECTRONIC, AMBIENT }

private fun filterRare(
    tracks: List<ExperimentalCatalog.Track>,
    filter: RareFilter,
): List<ExperimentalCatalog.Track> = tracks.filter { track ->
    when (filter) {
        RareFilter.ALL -> true
        RareFilter.SHORT -> track.durationSeconds in 1..240
        RareFilter.LONG -> track.durationSeconds > 240
        RareFilter.ELECTRONIC ->
            listOf("electronic", "techno", "house", "edm", "electro", "synth").any {
                track.genre?.contains(it, ignoreCase = true) == true
            }
        RareFilter.AMBIENT ->
            listOf("ambient", "chill", "downtempo").any {
                track.genre?.contains(it, ignoreCase = true) == true
            }
    }
}

@Composable
private fun RareTrackRow(
    track: ExperimentalCatalog.Track,
    active: Boolean,
    onClick: () -> Unit,
    onArtistClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.artwork,
            contentDescription = null,
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(11.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                modifier = Modifier.clickable(onClick = onArtistClick),
                text = track.artist + " · " + track.source.title +
                    (track.catalogProvider?.let { " · $it" } ?: "") +
                    if (track.playable) "" else " · ↗",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMenuClick) {
            Icon(painterResource(R.drawable.more_vert), contentDescription = "More")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnlineSearchResult(
    navController: NavController,
    viewModel: OnlineSearchViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val (experimentalEnabled, _) = rememberPreference(ExperimentalSourcesEnabledKey, false)
    var rareSource by remember { mutableStateOf<ExperimentalCatalog.Source?>(null) }
    var rareFilter by remember { mutableStateOf(RareFilter.ALL) }

    LaunchedEffect(experimentalEnabled) {
        if (!experimentalEnabled) {
            rareSource = null
            viewModel.disableExperimentalSearch()
        }
    }
    LaunchedEffect(rareSource, experimentalEnabled) {
        if (experimentalEnabled) {
            rareSource?.let(viewModel::ensureExperimentalSearch)
        }
    }

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val filteredPage by remember(filter) {
        derivedStateOf { filter?.value?.let { viewModel.viewStateMap[it] } }
    }
    val searchSummary = viewModel.summaryPage
    val rareStatus = rareSource?.let { viewModel.experimentalResults[it] }
    val rareTracks = (rareStatus as? OnlineSearchViewModel.ExperimentalSearchState.Ready)
        ?.tracks.orEmpty()
    val visibleRareTracks = remember(rareTracks, rareFilter) { filterRare(rareTracks, rareFilter) }

    LaunchedEffect(listState, rareSource) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == "loading" } }
            .collect { if (it && rareSource == null) viewModel.loadMore() }
    }

    val youtubeItemContent: @Composable LazyItemScope.(YTItem) -> Unit = { item ->
        val openMenu = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuState.show {
                when (item) {
                    is SongItem -> YouTubeSongMenu(
                        song = item, navController = navController, onDismiss = menuState::dismiss)
                    is AlbumItem -> YouTubeAlbumMenu(
                        albumItem = item, navController = navController, onDismiss = menuState::dismiss)
                    is ArtistItem -> YouTubeArtistMenu(
                        artist = item, onDismiss = menuState::dismiss)
                    is PlaylistItem -> YouTubePlaylistMenu(
                        playlist = item, coroutineScope = coroutineScope, onDismiss = menuState::dismiss)
                }
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            com.nikhil.yt.ui.component.LocalSongListVisuals provides
                com.nikhil.yt.ui.component.SongListVisuals(
                    rowHeight = 78.dp, thumbnailSize = 60.dp, thumbnailCornerRadius = 11.dp,
                ),
        ) {
        // Credits are resolved by the Innertube parser BEFORE the summary
        // appears. Per-row Room Flow subscriptions used to trigger a late
        // second-artist jump and incurred one observer per visible result.
        YouTubeListItem(
            item = item,
            isActive = when(item) {
                is SongItem -> mediaMetadata?.id == item.id
                is AlbumItem -> mediaMetadata?.album?.id == item.id
                else -> false
            },
            isPlaying = isPlaying,
            showSourceIcon = false,
            trailingContent = {
                IconButton(onClick = openMenu) {
                    Icon(painterResource(R.drawable.more_vert), contentDescription = null)
                }
            },
            modifier = Modifier.combinedClickable(
                onLongClick = openMenu,
                onClick = {
                    when(item) {
                        is SongItem -> if (mediaMetadata?.id == item.id) {
                            playerConnection.player.togglePlayPause()
                        } else {
                            playerConnection.playQueue(YouTubeQueue(
                                WatchEndpoint(videoId = item.id), item.toMediaMetadata()))
                        }
                        is AlbumItem -> navController.navigate("album/${item.id}")
                        is ArtistItem -> navController.navigate("artist/${item.id}")
                        is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                    }
                },
            ).animateItem(),
        )
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = LocalPlayerAwareWindowInsets.current
            .add(WindowInsets(top = AppBarHeight + SearchFilterHeight + 8.dp))
            .asPaddingValues(),
    ) {
        if (rareSource != null) {
            item(key = "experimental-heading") {
                Text(
                    text = rareSource!!.title + " · " + stringResource(R.string.capsule_discovery_experimental),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item(key = "experimental-filters") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        RareFilter.ALL to stringResource(R.string.filter_all),
                        RareFilter.SHORT to stringResource(R.string.capsule_discovery_short),
                        RareFilter.LONG to stringResource(R.string.capsule_discovery_long),
                        RareFilter.ELECTRONIC to stringResource(R.string.capsule_discovery_electronic),
                        RareFilter.AMBIENT to "Ambient",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = rareFilter == value,
                            onClick = { rareFilter = value },
                            label = { Text(label) },
                        )
                    }
                }
            }
            when (rareStatus) {
                null, OnlineSearchViewModel.ExperimentalSearchState.Loading ->
                    item(key = "rare-loading") {
                        ShimmerHost { repeat(6) { ListItemPlaceHolder() } }
                    }
                is OnlineSearchViewModel.ExperimentalSearchState.Failed ->
                    item(key = "rare-error") {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                stringResource(R.string.capsule_discovery_unavailable) +
                                    " · " + rareStatus.message.take(100),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                rareSource?.let(viewModel::retryExperimentalSearch)
                            }) {
                                Text(stringResource(R.string.capsule_discovery_retry))
                            }
                        }
                    }
                is OnlineSearchViewModel.ExperimentalSearchState.Ready -> {
                    if (visibleRareTracks.isEmpty()) {
                        item(key = "rare-empty") {
                            Column(Modifier.padding(20.dp)) {
                                Text(
                                    stringResource(R.string.no_results_found),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { rareSource?.let(viewModel::retryExperimentalSearch) }) {
                                    Text(stringResource(R.string.capsule_discovery_retry))
                                }
                            }
                        }
                    }
                    items(visibleRareTracks, key = { "rare-${it.mediaId}" }) { track ->
                        RareTrackRow(
                            track = track,
                            active = mediaMetadata?.id == track.mediaId,
                            onClick = {
                                if (track.playable) {
                                    if (mediaMetadata?.id == track.mediaId) {
                                        playerConnection.player.togglePlayPause()
                                    } else {
                                        ExperimentalTrackQueue.create(
                                            viewModel.query, visibleRareTracks, track
                                        )?.let(playerConnection::playQueue)
                                    }
                                } else {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.sourcePage)))
                                    }
                                }
                            },
                            onArtistClick = {
                                navController.navigate(ExperimentalArtistRoute.forTrack(track))
                            },
                            onMenuClick = {
                                menuState.show {
                                    ListItem(
                                        headlineContent = { Text(stringResource(R.string.view_artist)) },
                                        supportingContent = { Text(track.artist) },
                                        leadingContent = { Icon(painterResource(R.drawable.artist), null) },
                                        modifier = Modifier.clickable {
                                            menuState.dismiss()
                                            navController.navigate(ExperimentalArtistRoute.forTrack(track))
                                        },
                                    )
                                    ListItem(
                                        headlineContent = { Text(stringResource(R.string.capsule_discovery_open_source)) },
                                        supportingContent = { Text(track.source.title) },
                                        leadingContent = { Icon(painterResource(R.drawable.info), null) },
                                        modifier = Modifier.clickable {
                                            menuState.dismiss()
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.sourcePage)))
                                            }
                                        },
                                    )
                                    track.license?.let { license ->
                                        ListItem(
                                            headlineContent = { Text(stringResource(R.string.capsule_discovery_license)) },
                                            supportingContent = { Text(license, maxLines = 2) },
                                            modifier = Modifier.clickable {
                                                menuState.dismiss()
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(license)))
                                                }
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        } else if (filter == null) {
            if (searchSummary == null) {
                item(key = "summary-loading") {
                    ShimmerHost { repeat(8) { ListItemPlaceHolder() } }
                }
            } else {
                searchSummary.summaries.forEachIndexed { index, summary ->
                    if (index > 0) item(key = "divider_$index") {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            thickness = 0.5.dp,
                        )
                    }
                    item(key = "heading_$index") {
                        Text(
                            summary.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                    items(summary.items, key = { "${summary.title}/${it.id}/${summary.items.indexOf(it)}" },
                        itemContent = youtubeItemContent)
                }
                if (searchSummary.summaries.isEmpty()) item(key = "all-empty") {
                    EmptyPlaceholder(icon = R.drawable.search,
                        text = stringResource(R.string.no_results_found))
                }
            }
        } else {
            // Snapshot the Compose state into a stable local value for Kotlin smart casts.
            val page = filteredPage
            if (page == null) {
                item(key = "filtered-loading") {
                    ShimmerHost { repeat(6) { ListItemPlaceHolder() } }
                }
            } else {
                items(page.items.distinctBy { it.id },
                    key = { "filtered_${it.id}" }, itemContent = youtubeItemContent)
                if (page.continuation != null) item(key = "loading") {
                    ShimmerHost { repeat(3) { ListItemPlaceHolder() } }
                }
                if (page.items.isEmpty()) item(key = "filtered-empty") {
                    EmptyPlaceholder(icon = R.drawable.search,
                        text = stringResource(R.string.no_results_found))
                }
            }
        }
    }

    val selectedChip = when {
        rareSource == ExperimentalCatalog.Source.AUDIUS -> SearchChip.AUDIUS
        rareSource == ExperimentalCatalog.Source.OPENVERSE -> SearchChip.OPENVERSE
        rareSource == ExperimentalCatalog.Source.ARCHIVE -> SearchChip.ARCHIVE
        rareSource == ExperimentalCatalog.Source.CCMIXTER -> SearchChip.CCMIXTER
        filter == FILTER_SONG -> SearchChip.SONGS
        filter == FILTER_VIDEO -> SearchChip.VIDEOS
        filter == FILTER_ALBUM -> SearchChip.ALBUMS
        filter == FILTER_ARTIST -> SearchChip.ARTISTS
        filter == FILTER_COMMUNITY_PLAYLIST -> SearchChip.COMMUNITY_PLAYLISTS
        filter == FILTER_FEATURED_PLAYLIST -> SearchChip.FEATURED_PLAYLISTS
        else -> SearchChip.ALL
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Top)
                .add(WindowInsets(top = AppBarHeight))
        ).fillMaxWidth(),
    ) {
        ChipsRow(
            chips = buildList {
                add(SearchChip.ALL to stringResource(R.string.filter_all))
                add(SearchChip.SONGS to stringResource(R.string.filter_songs))
                add(SearchChip.VIDEOS to stringResource(R.string.filter_videos))
                add(SearchChip.ALBUMS to stringResource(R.string.filter_albums))
                add(SearchChip.ARTISTS to stringResource(R.string.filter_artists))
                add(SearchChip.COMMUNITY_PLAYLISTS to stringResource(R.string.filter_community_playlists))
                add(SearchChip.FEATURED_PLAYLISTS to stringResource(R.string.filter_featured_playlists))
                if (experimentalEnabled) {
                    add(SearchChip.AUDIUS to "Audius")
                }
            },
            currentValue = selectedChip,
            onValueUpdate = { chip ->
                rareFilter = RareFilter.ALL
                rareSource = when(chip) {
                    SearchChip.AUDIUS -> ExperimentalCatalog.Source.AUDIUS
                    SearchChip.OPENVERSE -> ExperimentalCatalog.Source.OPENVERSE
                    SearchChip.ARCHIVE -> ExperimentalCatalog.Source.ARCHIVE
                    SearchChip.CCMIXTER -> ExperimentalCatalog.Source.CCMIXTER
                    else -> null
                }
                viewModel.filter.value = when(chip) {
                    SearchChip.SONGS -> FILTER_SONG
                    SearchChip.VIDEOS -> FILTER_VIDEO
                    SearchChip.ALBUMS -> FILTER_ALBUM
                    SearchChip.ARTISTS -> FILTER_ARTIST
                    SearchChip.COMMUNITY_PLAYLISTS -> FILTER_COMMUNITY_PLAYLIST
                    SearchChip.FEATURED_PLAYLISTS -> FILTER_FEATURED_PLAYLIST
                    else -> null
                }
                coroutineScope.launch { listState.scrollToItem(0) }
            },
            icons = mapOf(
                SearchChip.ALL to R.drawable.search,
                SearchChip.SONGS to R.drawable.music_note,
                SearchChip.VIDEOS to R.drawable.slow_motion_video,
                SearchChip.ALBUMS to R.drawable.album,
                SearchChip.ARTISTS to R.drawable.person,
                SearchChip.COMMUNITY_PLAYLISTS to R.drawable.queue_music,
                SearchChip.FEATURED_PLAYLISTS to R.drawable.playlist_play,
                SearchChip.AUDIUS to R.drawable.ic_capsule_audius,
                SearchChip.OPENVERSE to R.drawable.ic_capsule_openverse,
                SearchChip.ARCHIVE to R.drawable.ic_capsule_archive_home,
                SearchChip.CCMIXTER to R.drawable.ic_capsule_ccmixter_home,
                SearchChip.RADIO to R.drawable.ic_capsule_radio_home,
            ),
            persistentIconValues = setOf(SearchChip.AUDIUS, SearchChip.OPENVERSE,
                SearchChip.ARCHIVE, SearchChip.CCMIXTER, SearchChip.RADIO),
            iconAccentColors = mapOf(
                SearchChip.AUDIUS to Color(0xFF7B39E8),
                SearchChip.OPENVERSE to Color(0xFF138C9B),
                SearchChip.ARCHIVE to Color(0xFFB87346),
                SearchChip.CCMIXTER to Color(0xFF18A995),
                SearchChip.RADIO to Color(0xFF26A99E),
            ),
        )
    }
}

/** Stable order, no unrelated artist search results or invented song credits. */
internal fun mergeSearchArtistCredits(vararg sources: List<String>): List<String> {
    val seen = hashSetOf<String>()
    return sources.asSequence().flatten()
        .map(String::trim).filter(String::isNotBlank)
        .filter { seen.add(it.lowercase(java.util.Locale.ROOT)) }.toList()
}
