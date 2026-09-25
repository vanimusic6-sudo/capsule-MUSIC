/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.soundcloud.soundCloudMediaId
import com.nikhil.yt.playback.queues.SoundCloudQueue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import com.nikhil.yt.constants.SoundCloudWebPreviewEnabledKey
import com.nikhil.yt.utils.rememberPreference
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.nikhil.yt.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.innertube.models.YTItem
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.LocalDownloadUtil
import com.nikhil.yt.R
import com.nikhil.yt.constants.AppBarHeight
import com.nikhil.yt.constants.SearchFilterHeight
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.playback.queues.YouTubeQueue
import com.nikhil.yt.ui.component.ChipsRow
import com.nikhil.yt.ui.component.EmptyPlaceholder
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.YouTubeListItem
import com.nikhil.yt.ui.component.SoundCloudUserListItem
import com.nikhil.yt.ui.component.SoundCloudTrackListItem
import com.nikhil.yt.ui.component.SoundCloudPlaylistListItem
import com.nikhil.yt.ui.component.shimmer.ListItemPlaceHolder
import com.nikhil.yt.ui.component.shimmer.ShimmerHost
import com.nikhil.yt.ui.menu.YouTubeAlbumMenu
import com.nikhil.yt.ui.menu.YouTubeArtistMenu
import com.nikhil.yt.ui.menu.YouTubePlaylistMenu
import com.nikhil.yt.ui.menu.YouTubeSongMenu
import com.nikhil.yt.ui.menu.SoundCloudTrackMenu
import com.nikhil.yt.viewmodels.OnlineSearchViewModel
import kotlinx.coroutines.launch

@Composable
private fun SearchSectionHeader(
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(
            horizontal = 20.dp,
            vertical = 12.dp,
        ),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SearchStatusText(
    message: Int,
) {
    Text(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(
            horizontal = 20.dp,
            vertical = 12.dp,
        ),
    )
}

private enum class SearchSourceFilter { ALL, YOUTUBE, SOUNDCLOUD }

private enum class SearchChip {
    ALL,
    YOUTUBE,
    SOUNDCLOUD,
    SONGS,
    VIDEOS,
    ALBUMS,
    ARTISTS,
    COMMUNITY_PLAYLISTS,
    FEATURED_PLAYLISTS,
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchResult(
    navController: NavController,
    viewModel: OnlineSearchViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val (showSoundCloudPreview, _) = rememberPreference(SoundCloudWebPreviewEnabledKey, false)
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()
    var sourceFilter by remember { mutableStateOf(SearchSourceFilter.ALL) }

    val soundCloudResult = viewModel.soundCloudResult
    val soundCloudPage =
        (soundCloudResult as? SoundCloudCatalog.Result.Success)?.value

    LaunchedEffect(showSoundCloudPreview) {
        if (showSoundCloudPreview) {
            viewModel.ensureSoundCloudSearch()
        }
    }

    val searchFilter by viewModel.filter.collectAsState()
    val searchSummary = viewModel.summaryPage
    val itemsPage by remember(searchFilter) {
        derivedStateOf {
            searchFilter?.value?.let {
                viewModel.viewStateMap[it]
            }
        }
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo.any { it.key == "loading" }
        }.collect { shouldLoadMore ->
            if (!shouldLoadMore) return@collect
            viewModel.loadMore()
        }
    }

    val ytItemContent: @Composable LazyItemScope.(YTItem) -> Unit = { item: YTItem ->
        val longClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuState.show {
                when (item) {
                    is SongItem ->
                        YouTubeSongMenu(
                            song = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )

                    is AlbumItem ->
                        YouTubeAlbumMenu(
                            albumItem = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )

                    is ArtistItem ->
                        YouTubeArtistMenu(
                            artist = item,
                            onDismiss = menuState::dismiss,
                        )

                    is PlaylistItem ->
                        YouTubePlaylistMenu(
                            playlist = item,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss,
                        )
                }
            }
        }
        YouTubeListItem(
            item = item,
            isActive =
            when (item) {
                is SongItem -> mediaMetadata?.id == item.id
                is AlbumItem -> mediaMetadata?.album?.id == item.id
                else -> false
            },
            isPlaying = isPlaying,
            showSourceIcon = showSoundCloudPreview,
            trailingContent = {
                IconButton(
                    onClick = longClick,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = null,
                    )
                }
            },
            modifier =
            Modifier
                .combinedClickable(
                    onClick = {
                        when (item) {
                            is SongItem -> {
                                if (item.id == mediaMetadata?.id) {
                                    playerConnection.player.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        YouTubeQueue(
                                            WatchEndpoint(videoId = item.id),
                                            item.toMediaMetadata()
                                        )
                                    )
                                }
                            }

                            is AlbumItem -> navController.navigate("album/${item.id}")
                            is ArtistItem -> navController.navigate("artist/${item.id}")
                            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                        }
                    },
                    onLongClick = longClick,
                )
                .animateItem(),
        )
    }

    val soundCloudTrackContent:
        @Composable LazyItemScope.(SoundCloudCatalog.Track) -> Unit = { track ->
            val mediaId = soundCloudMediaId(track.permalink)
            SoundCloudTrackListItem(
                track = track,
                isActive = mediaMetadata?.id == mediaId,
                isPlaying = isPlaying && mediaMetadata?.id == mediaId,
                onClick = {
                    if (mediaMetadata?.id == mediaId) {
                        playerConnection.player.togglePlayPause()
                    } else {
                        SoundCloudQueue.create(
                            title = viewModel.query,
                            tracks = soundCloudPage?.tracks.orEmpty(),
                            requestedStartUrl = track.permalink,
                            downloads = downloads,
                        )?.let(playerConnection::playQueue)
                    }
                },
                onArtistClick = {
                    navController.navigate(
                        "soundcloud/profile?url=" + android.net.Uri.encode(it),
                    )
                },
                onMoreClick = {
                    menuState.show {
                        SoundCloudTrackMenu(
                            track = track,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
                modifier = Modifier.animateItem(),
            )
        }

    val soundCloudPlaylistContent:
        @Composable LazyItemScope.(SoundCloudCatalog.Playlist) -> Unit = { playlist ->
            SoundCloudPlaylistListItem(
                playlist = playlist,
                onClick = {
                    navController.navigate(
                        "soundcloud/playlist?url=" +
                            android.net.Uri.encode(playlist.url),
                    )
                },
                modifier = Modifier.animateItem(),
            )
        }

    val soundCloudUserContent:
        @Composable LazyItemScope.(SoundCloudCatalog.User) -> Unit = { user ->
            SoundCloudUserListItem(
                user = user,
                followerText = stringResource(
                    R.string.capsule_soundcloud_followers,
                    user.followerCount.coerceAtLeast(0),
                ),
                onClick = {
                    navController.navigate(
                        "soundcloud/profile?url=" +
                            android.net.Uri.encode(user.url),
                    )
                },
                modifier = Modifier.animateItem(),
            )
        }

    LazyColumn(
        state = lazyListState,
        contentPadding =
            LocalPlayerAwareWindowInsets.current
                .add(WindowInsets(top = SearchFilterHeight + 8.dp))
                .asPaddingValues(),
    ) {
        val showYouTubeResults =
            sourceFilter != SearchSourceFilter.SOUNDCLOUD
        val showSoundCloudResults =
            showSoundCloudPreview &&
                sourceFilter != SearchSourceFilter.YOUTUBE

        if (searchFilter == null) {
            if (sourceFilter == SearchSourceFilter.SOUNDCLOUD) {
                when (val value = soundCloudResult) {
                    null -> {
                        item(key = "sc-loading") {
                            SearchStatusText(
                                R.string.capsule_soundcloud_loading,
                            )
                        }
                    }

                    SoundCloudCatalog.Result.RateLimited -> {
                        item(key = "sc-rate-limited") {
                            SearchStatusText(
                                R.string.capsule_soundcloud_rate_limited,
                            )
                        }
                    }

                    SoundCloudCatalog.Result.Unavailable -> {
                        item(key = "sc-unavailable") {
                            SearchStatusText(
                                R.string.capsule_soundcloud_request_failed,
                            )
                        }
                    }

                    is SoundCloudCatalog.Result.Success -> {
                        val page = value.value

                        if (page.tracks.isNotEmpty()) {
                            item(key = "sc-heading-tracks") {
                                SearchSectionHeader(
                                    stringResource(R.string.filter_songs),
                                )
                            }
                            items(
                                items = page.tracks,
                                key = { "sc-source-track-" + it.permalink },
                                itemContent = soundCloudTrackContent,
                            )
                        }

                        if (page.users.isNotEmpty()) {
                            item(key = "sc-heading-artists") {
                                SearchSectionHeader(
                                    stringResource(R.string.filter_artists),
                                )
                            }
                            items(
                                items = page.users,
                                key = { "sc-source-user-" + it.url },
                                itemContent = soundCloudUserContent,
                            )
                        }

                        if (page.playlists.isNotEmpty()) {
                            item(key = "sc-heading-playlists") {
                                SearchSectionHeader(
                                    stringResource(
                                        R.string.capsule_soundcloud_playlists,
                                    ),
                                )
                            }
                            items(
                                items = page.playlists,
                                key = { "sc-source-playlist-" + it.url },
                                itemContent = soundCloudPlaylistContent,
                            )
                        }

                        if (
                            page.tracks.isEmpty() &&
                            page.users.isEmpty() &&
                            page.playlists.isEmpty()
                        ) {
                            item(key = "sc-empty") {
                                EmptyPlaceholder(
                                    icon = R.drawable.search,
                                    text = stringResource(
                                        R.string.no_results_found,
                                    ),
                                )
                            }
                        }
                    }
                }
            } else if (showYouTubeResults) {
                if (searchSummary == null) {
                    item(key = "summary-loading") {
                        ShimmerHost {
                            repeat(8) {
                                ListItemPlaceHolder()
                            }
                        }
                    }
                } else {
                    val summaries = searchSummary.summaries
                    val songSummaryIndex =
                        summaries.indexOfFirst { summary ->
                            summary.items.any { it is SongItem }
                        }
                    val artistSummaryIndex =
                        summaries.indexOfFirst { summary ->
                            summary.items.any { it is ArtistItem }
                        }
                    val playlistSummaryIndex =
                        summaries.indexOfFirst { summary ->
                            summary.items.any { it is PlaylistItem }
                        }

                    summaries.forEachIndexed { index, summary ->
                        if (index > 0) {
                            item(key = "divider_$index") {
                                HorizontalDivider(
                                    modifier = Modifier.padding(
                                        horizontal = 20.dp,
                                        vertical = 4.dp,
                                    ),
                                    thickness = 0.5.dp,
                                    color =
                                        MaterialTheme.colorScheme.outlineVariant
                                            .copy(alpha = 0.4f),
                                )
                            }
                        }

                        item(key = "heading_$index") {
                            SearchSectionHeader(summary.title)
                        }

                        items(
                            items = summary.items,
                            key = {
                                "${summary.title}/${it.id}/${summary.items.indexOf(it)}"
                            },
                            itemContent = ytItemContent,
                        )

                        // SoundCloud has no separate block in "All". It joins
                        // the matching YouTube section after YouTube results.
                        if (
                            showSoundCloudResults &&
                            soundCloudPage != null
                        ) {
                            when (index) {
                                songSummaryIndex -> {
                                    items(
                                        items = soundCloudPage.tracks,
                                        key = {
                                            "sc-all-track-" + it.permalink
                                        },
                                        itemContent = soundCloudTrackContent,
                                    )
                                }

                                artistSummaryIndex -> {
                                    items(
                                        items = soundCloudPage.users,
                                        key = {
                                            "sc-all-user-" + it.url
                                        },
                                        itemContent = soundCloudUserContent,
                                    )
                                }

                                playlistSummaryIndex -> {
                                    items(
                                        items = soundCloudPage.playlists,
                                        key = {
                                            "sc-all-playlist-" + it.url
                                        },
                                        itemContent = soundCloudPlaylistContent,
                                    )
                                }
                            }
                        }

                        item(key = "summary-space_$index") {
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    // If YouTube did not return a section of a given type, do
                    // not lose valid SoundCloud results. Use the same standard
                    // section chrome rather than a SoundCloud-specific layout.
                    if (
                        showSoundCloudResults &&
                        soundCloudPage != null
                    ) {
                        if (
                            songSummaryIndex < 0 &&
                            soundCloudPage.tracks.isNotEmpty()
                        ) {
                            item(key = "fallback-heading-tracks") {
                                SearchSectionHeader(
                                    stringResource(R.string.filter_songs),
                                )
                            }
                            items(
                                items = soundCloudPage.tracks,
                                key = {
                                    "sc-fallback-track-" + it.permalink
                                },
                                itemContent = soundCloudTrackContent,
                            )
                        }
                        if (
                            artistSummaryIndex < 0 &&
                            soundCloudPage.users.isNotEmpty()
                        ) {
                            item(key = "fallback-heading-artists") {
                                SearchSectionHeader(
                                    stringResource(R.string.filter_artists),
                                )
                            }
                            items(
                                items = soundCloudPage.users,
                                key = { "sc-fallback-user-" + it.url },
                                itemContent = soundCloudUserContent,
                            )
                        }
                        if (
                            playlistSummaryIndex < 0 &&
                            soundCloudPage.playlists.isNotEmpty()
                        ) {
                            item(key = "fallback-heading-playlists") {
                                SearchSectionHeader(
                                    stringResource(
                                        R.string.capsule_soundcloud_playlists,
                                    ),
                                )
                            }
                            items(
                                items = soundCloudPage.playlists,
                                key = {
                                    "sc-fallback-playlist-" + it.url
                                },
                                itemContent = soundCloudPlaylistContent,
                            )
                        }
                    }

                    if (
                        summaries.isEmpty() &&
                        (
                            !showSoundCloudResults ||
                                soundCloudPage == null ||
                                (
                                    soundCloudPage.tracks.isEmpty() &&
                                        soundCloudPage.users.isEmpty() &&
                                        soundCloudPage.playlists.isEmpty()
                                )
                        )
                    ) {
                        item(key = "all-empty") {
                            EmptyPlaceholder(
                                icon = R.drawable.search,
                                text = stringResource(
                                    R.string.no_results_found,
                                ),
                            )
                        }
                    }
                }
            }
        } else {
            // Filtered lists are one list per content type. YouTube comes first,
            // then matching SoundCloud entities with the exact same row chrome.
            if (showYouTubeResults) {
                if (itemsPage == null) {
                    item(key = "filtered-loading") {
                        ShimmerHost {
                            repeat(6) {
                                ListItemPlaceHolder()
                            }
                        }
                    }
                } else {
                    items(
                        items = itemsPage.items.distinctBy { it.id },
                        key = { "filtered_${it.id}" },
                        itemContent = ytItemContent,
                    )

                    if (itemsPage.continuation != null) {
                        item(key = "loading") {
                            ShimmerHost {
                                repeat(3) {
                                    ListItemPlaceHolder()
                                }
                            }
                        }
                    }
                }
            }

            if (
                showSoundCloudResults &&
                soundCloudPage != null
            ) {
                when (searchFilter) {
                    FILTER_SONG -> {
                        items(
                            items = soundCloudPage.tracks,
                            key = { "sc-filter-track-" + it.permalink },
                            itemContent = soundCloudTrackContent,
                        )
                    }

                    FILTER_ARTIST -> {
                        items(
                            items = soundCloudPage.users,
                            key = { "sc-filter-user-" + it.url },
                            itemContent = soundCloudUserContent,
                        )
                    }

                    FILTER_COMMUNITY_PLAYLIST,
                    FILTER_FEATURED_PLAYLIST -> {
                        items(
                            items = soundCloudPage.playlists,
                            key = {
                                "sc-filter-playlist-" + it.url
                            },
                            itemContent = soundCloudPlaylistContent,
                        )
                    }
                }
            }

            if (itemsPage != null) {
                val soundCloudEmptyForFilter =
                    when (searchFilter) {
                        FILTER_SONG ->
                            soundCloudPage?.tracks.isNullOrEmpty()
                        FILTER_ARTIST ->
                            soundCloudPage?.users.isNullOrEmpty()
                        FILTER_COMMUNITY_PLAYLIST,
                        FILTER_FEATURED_PLAYLIST ->
                            soundCloudPage?.playlists.isNullOrEmpty()
                        else -> true
                    }

                if (
                    itemsPage.items.isEmpty() &&
                    (
                        !showSoundCloudResults ||
                            soundCloudEmptyForFilter
                    )
                ) {
                    item(key = "filtered-empty") {
                        EmptyPlaceholder(
                            icon = R.drawable.search,
                            text = stringResource(
                                R.string.no_results_found,
                            ),
                        )
                    }
                }
            }
        }
    }

    val selectedChip = when {
        sourceFilter == SearchSourceFilter.YOUTUBE -> SearchChip.YOUTUBE
        sourceFilter == SearchSourceFilter.SOUNDCLOUD -> SearchChip.SOUNDCLOUD
        searchFilter == FILTER_SONG -> SearchChip.SONGS
        searchFilter == FILTER_VIDEO -> SearchChip.VIDEOS
        searchFilter == FILTER_ALBUM -> SearchChip.ALBUMS
        searchFilter == FILTER_ARTIST -> SearchChip.ARTISTS
        searchFilter == FILTER_COMMUNITY_PLAYLIST -> SearchChip.COMMUNITY_PLAYLISTS
        searchFilter == FILTER_FEATURED_PLAYLIST -> SearchChip.FEATURED_PLAYLISTS
        else -> SearchChip.ALL
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .only(WindowInsetsSides.Top)
                    .add(WindowInsets(top = AppBarHeight)),
            )
            .fillMaxWidth(),
    ) {
        ChipsRow(
            chips = buildList {
                add(SearchChip.ALL to stringResource(R.string.filter_all))
                if (showSoundCloudPreview) {
                    add(SearchChip.YOUTUBE to "YouTube")
                    add(SearchChip.SOUNDCLOUD to "SoundCloud")
                }
                add(SearchChip.SONGS to stringResource(R.string.filter_songs))
                add(SearchChip.VIDEOS to stringResource(R.string.filter_videos))
                add(SearchChip.ALBUMS to stringResource(R.string.filter_albums))
                add(SearchChip.ARTISTS to stringResource(R.string.filter_artists))
                add(
                    SearchChip.COMMUNITY_PLAYLISTS to
                        stringResource(R.string.filter_community_playlists),
                )
                add(
                    SearchChip.FEATURED_PLAYLISTS to
                        stringResource(R.string.filter_featured_playlists),
                )
            },
            currentValue = selectedChip,
            onValueUpdate = { chip ->
                when (chip) {
                    SearchChip.ALL -> {
                        sourceFilter = SearchSourceFilter.ALL
                        viewModel.filter.value = null
                    }
                    SearchChip.YOUTUBE -> {
                        sourceFilter = SearchSourceFilter.YOUTUBE
                        viewModel.filter.value = null
                    }
                    SearchChip.SOUNDCLOUD -> {
                        sourceFilter = SearchSourceFilter.SOUNDCLOUD
                        viewModel.filter.value = null
                    }
                    SearchChip.SONGS -> {
                        sourceFilter = SearchSourceFilter.ALL
                        viewModel.filter.value = FILTER_SONG
                    }
                    SearchChip.VIDEOS -> {
                        sourceFilter = SearchSourceFilter.ALL
                        viewModel.filter.value = FILTER_VIDEO
                    }
                    SearchChip.ALBUMS -> {
                        sourceFilter = SearchSourceFilter.ALL
                        viewModel.filter.value = FILTER_ALBUM
                    }
                    SearchChip.ARTISTS -> {
                        sourceFilter = SearchSourceFilter.ALL
                        viewModel.filter.value = FILTER_ARTIST
                    }
                    SearchChip.COMMUNITY_PLAYLISTS -> {
                        sourceFilter = SearchSourceFilter.ALL
                        viewModel.filter.value = FILTER_COMMUNITY_PLAYLIST
                    }
                    SearchChip.FEATURED_PLAYLISTS -> {
                        sourceFilter = SearchSourceFilter.ALL
                        viewModel.filter.value = FILTER_FEATURED_PLAYLIST
                    }
                }
                coroutineScope.launch {
                    lazyListState.animateScrollToItem(0)
                }
            },
            icons = mapOf(
                SearchChip.ALL to R.drawable.search,
                SearchChip.YOUTUBE to R.drawable.youtube_source,
                SearchChip.SOUNDCLOUD to R.drawable.soundcloud_source,
                SearchChip.SONGS to R.drawable.music_note,
                SearchChip.VIDEOS to R.drawable.slow_motion_video,
                SearchChip.ALBUMS to R.drawable.album,
                SearchChip.ARTISTS to R.drawable.person,
                SearchChip.COMMUNITY_PLAYLISTS to R.drawable.queue_music,
                SearchChip.FEATURED_PLAYLISTS to R.drawable.playlist_play,
            ),
        )
    }

}
