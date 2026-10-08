/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.screens

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import kotlinx.coroutines.CoroutineScope
import com.nikhil.yt.R
import com.nikhil.yt.constants.GridThumbnailHeight
import com.nikhil.yt.constants.ListItemHeight
import com.nikhil.yt.constants.ListThumbnailSize
import com.nikhil.yt.constants.ThumbnailCornerRadius
import com.nikhil.yt.db.entities.Album
import com.nikhil.yt.db.entities.Artist
import com.nikhil.yt.db.entities.LocalItem
import com.nikhil.yt.db.entities.Playlist
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.innertube.models.YTItem
import com.nikhil.yt.innertube.pages.HomePage
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.playback.PlayerConnection
import com.nikhil.yt.playback.queues.YouTubeQueue
import com.nikhil.yt.ui.component.AlbumGridItem
import com.nikhil.yt.ui.component.ArtistGridItem
import com.nikhil.yt.ui.component.MenuState
import com.nikhil.yt.ui.component.NavigationTitle
import com.nikhil.yt.ui.component.SongGridItem
import com.nikhil.yt.ui.component.SongListItem
import com.nikhil.yt.ui.component.YouTubeGridItem
import com.nikhil.yt.ui.component.YouTubeListItem
import com.nikhil.yt.ui.component.shimmer.GridItemPlaceHolder
import com.nikhil.yt.ui.component.shimmer.ShimmerHost
import com.nikhil.yt.ui.component.shimmer.TextPlaceholder
import com.nikhil.yt.ui.menu.AlbumMenu
import com.nikhil.yt.ui.menu.ArtistMenu
import com.nikhil.yt.ui.menu.SongMenu
import com.nikhil.yt.ui.menu.YouTubeAlbumMenu
import com.nikhil.yt.ui.menu.YouTubeArtistMenu
import com.nikhil.yt.ui.menu.YouTubePlaylistMenu
import com.nikhil.yt.ui.menu.YouTubeSongMenu
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import com.nikhil.yt.innertube.pages.MoodAndGenres
import kotlin.math.min

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nikhil.yt.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QuickPicksSection(
    quickPicks: List<Song>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier
) {
    val distinctQuickPicks = remember(quickPicks) { quickPicks.distinctBy { it.id } }
    val density = LocalDensity.current
    val requestWidthPx = with(density) { 250.dp.roundToPx().coerceAtLeast(1) }
    val requestHeightPx = with(density) { 290.dp.roundToPx().coerceAtLeast(1) }

    HorizontalUncontainedCarousel(
        state = rememberCarouselState { distinctQuickPicks.size },
        itemWidth = 250.dp,
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(290.dp)
    ) { index ->
        val song = distinctQuickPicks[index]
        val isActive = song.id == mediaMetadata?.id
        val context = LocalContext.current
        val artworkRequest =
            remember(song.song.thumbnailUrl, requestWidthPx, requestHeightPx) {
                ImageRequest.Builder(context)
                    .data(song.song.thumbnailUrl)
                    .size(Size(requestWidthPx, requestHeightPx))
                    .crossfade(true)
                    .build()
            }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .maskClip(MaterialTheme.shapes.extraLarge)
                .maskBorder(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    MaterialTheme.shapes.extraLarge
                )
                .combinedClickable(
                    onClick = {
                        if (isActive) {
                            playerConnection.player.togglePlayPause()
                        } else {
                            playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuState.show {
                            SongMenu(
                                originalSong = song,
                                navController = navController,
                                onDismiss = menuState::dismiss
                            )
                        }
                    }
                )
        ) {
            AsyncImage(
                model = artworkRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            )
                        )
                    )
            )

            if (isActive && isPlaying) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(32.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.volume_up),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = song.song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Continue Listening keeps the swipe/page language of Quick Picks, but not its recommendation
 * logic. Mixed local items live in compact, tappable rows so the section is fast to scan and one
 * horizontal gesture advances a complete group instead of drifting through an endless grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeepListeningSection(
    keepListening: List<LocalItem>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    val distinctItems = remember(keepListening) {
        keepListening.distinctBy { item ->
            when (item) {
                is Song -> "song_${item.id}"
                is Album -> "album_${item.id}"
                is Artist -> "artist_${item.id}"
                is Playlist -> "playlist_${item.id}"
            }
        }
    }
    val pages = remember(distinctItems) { distinctItems.chunked(4) }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState { pages.size }

    Column(modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(ListItemHeight * 4 + 8.dp)
        ) { pageIndex ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f))
                    .padding(vertical = 4.dp)
            ) {
                pages.getOrNull(pageIndex).orEmpty().forEach { item ->
                    KeepListeningRow(
                        item = item,
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

        if (pages.size > 1) {
            val indicatorProgress by remember(pagerState, pages.size) {
                derivedStateOf {
                    (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        .coerceIn(0f, (pages.size - 1).toFloat())
                }
            }
            val slotWidth = 18.dp
            val slotGap = 6.dp
            val indicatorWidth = slotWidth * pages.size + slotGap * (pages.size - 1)
            val slotStepPx = with(LocalDensity.current) { (slotWidth + slotGap).toPx() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(indicatorWidth)
                        .height(8.dp)
                ) {
                    Row(
                        horizontalArrangement =
                            androidx.compose.foundation.layout.Arrangement.spacedBy(slotGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(pages.size) {
                            Box(
                                modifier = Modifier
                                    .width(slotWidth)
                                    .height(8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(slotWidth)
                            .height(6.dp)
                            .graphicsLayer {
                                translationX = slotStepPx * indicatorProgress
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeepListeningRow(
    item: LocalItem,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
) {
    val isActive = when (item) {
        is Song -> item.id == mediaMetadata?.id
        is Album -> item.id == mediaMetadata?.album?.id
        else -> false
    }
    val subtitle = when (item) {
        is Song -> item.artists.joinToString { it.name }
        is Album -> item.artists.joinToString { it.name }
        is Artist -> ""
        is Playlist -> ""
    }
    val thumbnail = when (item) {
        is Playlist -> item.thumbnails.firstOrNull()
        else -> item.thumbnailUrl
    }
    val thumbnailShape =
        if (item is Artist) CircleShape else RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ListItemHeight)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = {
                    when (item) {
                        is Song -> {
                            if (isActive) {
                                playerConnection.player.togglePlayPause()
                            } else {
                                playerConnection.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                            }
                        }
                        is Album -> navController.navigate("album/${item.id}")
                        is Artist -> navController.navigate("artist/${item.id}")
                        is Playlist -> Unit
                    }
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuState.show {
                        when (item) {
                            is Song -> SongMenu(
                                originalSong = item,
                                navController = navController,
                                onDismiss = menuState::dismiss
                            )
                            is Album -> AlbumMenu(
                                originalAlbum = item,
                                navController = navController,
                                onDismiss = menuState::dismiss
                            )
                            is Artist -> ArtistMenu(
                                originalArtist = item,
                                coroutineScope = scope,
                                onDismiss = menuState::dismiss
                            )
                            is Playlist -> Unit
                        }
                    }
                }
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(ListThumbnailSize)
                .clip(thumbnailShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isActive && isPlaying) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.volume_up),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                menuState.show {
                    when (item) {
                        is Song -> SongMenu(
                            originalSong = item,
                            navController = navController,
                            onDismiss = menuState::dismiss
                        )
                        is Album -> AlbumMenu(
                            originalAlbum = item,
                            navController = navController,
                            onDismiss = menuState::dismiss
                        )
                        is Artist -> ArtistMenu(
                            originalArtist = item,
                            coroutineScope = scope,
                            onDismiss = menuState::dismiss
                        )
                        is Playlist -> Unit
                    }
                }
            }
        ) {
            Icon(
                painter = painterResource(R.drawable.more_vert),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Forgotten Favorites section - horizontal grid of songs
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ForgottenFavoritesSection(
    forgottenFavorites: List<Song>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    horizontalLazyGridItemWidth: Dp,
    lazyGridState: LazyGridState,
    snapLayoutInfoProvider: SnapLayoutInfoProvider,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier
) {
    val rows = min(4, forgottenFavorites.size)
    val distinctForgottenFavorites = remember(forgottenFavorites) { forgottenFavorites.distinctBy { it.id } }
    
    LazyHorizontalGrid(
        state = lazyGridState,
        rows = GridCells.Fixed(rows),
        flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
        contentPadding = WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal)
            .asPaddingValues(),
        modifier = modifier
            .fillMaxWidth()
            .height(ListItemHeight * rows)
    ) {
        items(
            items = distinctForgottenFavorites,
            key = { it.id }
        ) { song ->
            SongListItem(
                song = song,
                showInLibraryIcon = true,
                isActive = song.id == mediaMetadata?.id,
                isPlaying = isPlaying,
                isSwipeable = false,
                trailingContent = {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                SongMenu(
                                    originalSong = song,
                                    navController = navController,
                                    onDismiss = menuState::dismiss
                                )
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null
                        )
                    }
                },
                modifier = Modifier
                    .width(horizontalLazyGridItemWidth)
                    .combinedClickable(
                        onClick = {
                            if (song.id == mediaMetadata?.id) {
                                playerConnection.player.togglePlayPause()
                            } else {
                                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                SongMenu(
                                    originalSong = song,
                                    navController = navController,
                                    onDismiss = menuState::dismiss
                                )
                            }
                        }
                    )
            )
        }
    }
}

/**
 * Account Playlists section - horizontal row of YouTube playlists
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccountPlaylistsSection(
    accountPlaylists: List<PlaylistItem>,
    accountName: String,
    accountImageUrl: String?,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    val distinctPlaylists = remember(accountPlaylists) { accountPlaylists.distinctBy { it.id } }
    
    LazyRow(
        contentPadding = WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal)
            .asPaddingValues(),
        modifier = modifier
    ) {
        items(
            items = distinctPlaylists,
            key = { it.id }
        ) { item ->
            YouTubeGridItemWrapper(
                item = item,
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

/**
 * HomePage Section - a single section from YouTube home page
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePageSectionContent(
    section: HomePage.Section,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    LazyRow(
        contentPadding = WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal)
            .asPaddingValues(),
        modifier = modifier
    ) {
        items(
            items = section.items,
            key = { it.id }
        ) { item ->
            YouTubeGridItemWrapper(
                item = item,
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

/**
 * Mood and Genres section
 */
@Composable
fun MoodAndGenresSection(
    moodAndGenres: List<MoodAndGenres.Item>,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    LazyHorizontalGrid(
        rows = GridCells.Fixed(4),
        contentPadding = PaddingValues(6.dp),
        modifier = modifier.height((MoodAndGenresButtonHeight + 12.dp) * 4 + 12.dp)
    ) {
        items(moodAndGenres) {
            MoodAndGenresButton(
                title = it.title,
                onClick = {
                    navController.navigate("youtube_browse/${it.endpoint.browseId}?params=${it.endpoint.params}")
                },
                modifier = Modifier
                    .padding(6.dp)
                    .width(180.dp)
            )
        }
    }
}

/**
 * Loading shimmer for home page sections
 */
@Composable
fun HomeLoadingShimmer(modifier: Modifier = Modifier) {
    ShimmerHost(modifier = modifier) {
        TextPlaceholder(
            height = 36.dp,
            modifier = Modifier
                .padding(12.dp)
                .width(250.dp),
        )
        LazyRow {
            items(4) {
                GridItemPlaceHolder()
            }
        }
    }
}

/**
 * Loading shimmer for mood and genres
 */
@Composable
fun MoodAndGenresLoadingShimmer(modifier: Modifier = Modifier) {
    ShimmerHost(modifier = modifier) {
        TextPlaceholder(
            height = 36.dp,
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 12.dp)
                .width(250.dp),
        )
        repeat(4) {
            Row {
                repeat(2) {
                    TextPlaceholder(
                        height = MoodAndGenresButtonHeight,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .width(200.dp)
                    )
                }
            }
        }
    }
}

// ============== Helper Composables ==============

/**
 * Wrapper for YouTube grid items with click handling
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YouTubeGridItemWrapper(
    item: YTItem,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    YouTubeGridItem(
        item = item,
        isActive = item.id in listOf(mediaMetadata?.album?.id, mediaMetadata?.id),
        isPlaying = isPlaying,
        coroutineScope = scope,
        // Let the item choose its natural Home aspect ratio (songs are wide, albums stay square)
        // instead of forcing every recommendation into the same square card.
        modifier = modifier.combinedClickable(
            onClick = {
                when (item) {
                    is SongItem -> playerConnection.playQueue(
                        YouTubeQueue(
                            item.endpoint ?: WatchEndpoint(videoId = item.id),
                            item.toMediaMetadata()
                        )
                    )
                    is AlbumItem -> navController.navigate("album/${item.id}")
                    is ArtistItem -> navController.navigate("artist/${item.id}")
                    is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                }
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                menuState.show {
                    when (item) {
                        is SongItem -> YouTubeSongMenu(
                            song = item,
                            navController = navController,
                            onDismiss = menuState::dismiss
                        )
                        is AlbumItem -> YouTubeAlbumMenu(
                            albumItem = item,
                            navController = navController,
                            onDismiss = menuState::dismiss
                        )
                        is ArtistItem -> YouTubeArtistMenu(
                            artist = item,
                            onDismiss = menuState::dismiss
                        )
                        is PlaylistItem -> YouTubePlaylistMenu(
                            playlist = item,
                            coroutineScope = scope,
                            onDismiss = menuState::dismiss
                        )
                    }
                }
            }
        )
    )
}

/**
 * Local item grid item for songs, albums, artists
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalGridItem(
    item: LocalItem,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    when (item) {
        is Song -> SongGridItem(
            song = item,
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (item.id == mediaMetadata?.id) {
                            playerConnection.player.togglePlayPause()
                        } else {
                            playerConnection.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuState.show {
                            SongMenu(
                                originalSong = item,
                                navController = navController,
                                onDismiss = menuState::dismiss
                            )
                        }
                    }
                ),
            isActive = item.id == mediaMetadata?.id,
            isPlaying = isPlaying
        )

        is Album -> AlbumGridItem(
            album = item,
            isActive = item.id == mediaMetadata?.album?.id,
            isPlaying = isPlaying,
            coroutineScope = scope,
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { navController.navigate("album/${item.id}") },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuState.show {
                            AlbumMenu(
                                originalAlbum = item,
                                navController = navController,
                                onDismiss = menuState::dismiss
                            )
                        }
                    }
                )
        )

        is Artist -> ArtistGridItem(
            artist = item,
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { navController.navigate("artist/${item.id}") },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuState.show {
                            ArtistMenu(
                                originalArtist = item,
                                coroutineScope = scope,
                                onDismiss = menuState::dismiss
                            )
                        }
                    }
                )
        )

        is Playlist -> { /* Not displayed */ }
    }
}

/**
 * Account playlist navigation title with image
 */
@Composable
fun AccountPlaylistsTitle(
    accountName: String,
    accountImageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationTitle(
        label = stringResource(R.string.your_youtube_playlists),
        title = accountName,
        thumbnail = {
            if (accountImageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(accountImageUrl)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .diskCacheKey(accountImageUrl)
                        .crossfade(true)
                        .build(),
                    placeholder = painterResource(id = R.drawable.person),
                    error = painterResource(id = R.drawable.person),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(ListThumbnailSize)
                        .clip(CircleShape)
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.person),
                    contentDescription = null,
                    modifier = Modifier.size(ListThumbnailSize)
                )
            }
        },
        onClick = onClick,
        modifier = modifier
    )
}

/**
 * HomePage section navigation title
 */
@Composable
fun HomePageSectionTitle(
    section: HomePage.Section,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    NavigationTitle(
        title = section.title,
        label = section.label,
        thumbnail = section.thumbnail?.let { thumbnailUrl ->
            {
                val shape = if (section.endpoint?.isArtistEndpoint == true) CircleShape 
                    else RoundedCornerShape(ThumbnailCornerRadius)
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(ListThumbnailSize)
                        .clip(shape)
                )
            }
        },
        onClick = section.endpoint?.browseId?.let { browseId ->
            {
                if (browseId == "FEmusic_moods_and_genres")
                    navController.navigate("mood_and_genres")
                else
                    navController.navigate("browse/$browseId")
            }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.AccountPlaylistsContainer(
    viewModel: HomeViewModel,
    accountName: String?,
    accountImageUrl: String?,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope
) {
    item {
        val accountPlaylists by viewModel.accountPlaylists.collectAsState()
        
        // Check if list is not null and not empty
        val currentPlaylists = accountPlaylists
        if (!currentPlaylists.isNullOrEmpty()) {
            Column {
                 AccountPlaylistsTitle(
                    accountName = accountName ?: "",
                    accountImageUrl = accountImageUrl,
                    onClick = { navController.navigate("library") },
                    modifier = Modifier
                )
                AccountPlaylistsSection(
                    accountPlaylists = currentPlaylists,
                    accountName = accountName ?: "",
                    accountImageUrl = accountImageUrl,
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
}

/**
 * Quick picks list style section (below carousel)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickPicksListSection(
    quickPicks: List<Song>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier
) {
    val distinctQuickPicks = remember(quickPicks) { quickPicks.distinctBy { it.id } }
    // Split into pages of 4 songs each
    val pages = remember(distinctQuickPicks) { distinctQuickPicks.chunked(4) }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState { pages.size }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Quick picks",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Play all",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { pageIndex ->
            val pageSongs = pages.getOrNull(pageIndex) ?: emptyList()
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    pageSongs.forEach { song ->
                        val isActive = song.id == mediaMetadata?.id
                        SongListItem(
                            song = song,
                            isActive = isActive,
                            isPlaying = isPlaying,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menuState.show {
                                            SongMenu(
                                                originalSong = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss
                                            )
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (isActive) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menuState.show {
                                            SongMenu(
                                                originalSong = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss
                                            )
                                        }
                                    }
                                )
                        )
                    }
                }
            }
        }

        // Page indicator dots
        if (pages.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                repeat(pages.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (isSelected) 8.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                    )
                }
            }
        }
    }
}

/**
 * Community playlists - horizontal scroll, 2 items visible at a time
 */
@Composable
fun CommunityPlaylistsSection(
    section: HomePage.Section,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = section.title ?: "From the community",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(
                items = section.items,
                key = { it.id }
            ) { item ->
                Box(modifier = Modifier.width(160.dp)) {
                    YouTubeGridItemWrapper(
                        item = item,
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
    }
}

/**
 * One unified taste shelf. It intentionally does not expose individual seed titles; the user sees
 * a coherent recommendation set rather than a stack of noisy "Similar to X" rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasteRecommendationsSection(
    suggestions: List<SongItem>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier,
) {
    val distinctSuggestions = remember(suggestions) { suggestions.distinctBy { it.id } }
    if (distinctSuggestions.isEmpty()) return

    val pages = remember(distinctSuggestions) { distinctSuggestions.chunked(4) }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState { pages.size }

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(R.string.taste_recommendations),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.taste_recommendations_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(ListItemHeight * 4 + 8.dp)
        ) { pageIndex ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
                    .padding(vertical = 4.dp)
            ) {
                pages.getOrNull(pageIndex).orEmpty().forEach { song ->
                    val isActive = song.id == mediaMetadata?.id
                    YouTubeListItem(
                        item = song,
                        isActive = isActive,
                        isPlaying = isPlaying,
                        isSwipeable = true,
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        YouTubeSongMenu(
                                            song = song,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_vert),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (isActive) {
                                        playerConnection.player.togglePlayPause()
                                    } else {
                                        playerConnection.playQueue(
                                            YouTubeQueue(
                                                song.endpoint ?: WatchEndpoint(videoId = song.id),
                                                song.toMediaMetadata()
                                            )
                                        )
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        YouTubeSongMenu(
                                            song = song,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                }
                            )
                    )
                }
            }
        }

        if (pages.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pages.size) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .height(6.dp)
                            .width(if (selected) 18.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }
        }
    }
}
