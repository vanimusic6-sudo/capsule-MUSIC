/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */


package com.nikhil.yt.ui.screens.artist

import com.nikhil.yt.ui.utils.artistPortraitUrl
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.ui.util.fastForEach
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.db.entities.ArtistEntity
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.playback.queues.ListQueue
import com.nikhil.yt.playback.queues.YouTubeQueue
import com.nikhil.yt.ui.component.ArtistHero
import com.nikhil.yt.ui.component.ArtistToolbarArtworkHeight
import com.nikhil.yt.ui.component.artistHeroArtworkHeight
import com.nikhil.yt.ui.component.ArtistToolbar
import com.nikhil.yt.ui.component.StandardChrome
import com.nikhil.yt.ui.component.AlbumGridItem
import com.nikhil.yt.ui.component.HideOnScrollFAB
import com.nikhil.yt.ui.component.IconButton
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.LocalSongListVisuals
import com.nikhil.yt.ui.component.NavigationTitle
import com.nikhil.yt.ui.component.SongListItem
import com.nikhil.yt.ui.component.SongListVisuals
import com.nikhil.yt.ui.component.YouTubeGridItem
import com.nikhil.yt.ui.component.YouTubeListItem
import com.nikhil.yt.ui.component.shimmer.ListItemPlaceHolder
import com.nikhil.yt.ui.component.shimmer.ShimmerHost
import com.nikhil.yt.ui.menu.AlbumMenu
import com.nikhil.yt.ui.menu.SongMenu
import com.nikhil.yt.ui.menu.YouTubeAlbumMenu
import com.nikhil.yt.ui.menu.YouTubeArtistMenu
import com.nikhil.yt.ui.menu.YouTubePlaylistMenu
import com.nikhil.yt.ui.menu.YouTubeSongMenu
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.rememberPreference
import com.nikhil.yt.viewmodels.ArtistViewModel

private data class ArtistLatestRelease(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val year: Int?,
)

private val ArtistTrackRowHeight = 72.dp
private val ArtistTrackArtworkSize = 56.dp
private val ArtistTrackArtworkCornerRadius = 10.dp
private val ArtistLatestReleaseArtworkSize = 112.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val artistPage = viewModel.artistPage
    val libraryArtist by viewModel.libraryArtist.collectAsState()
    val librarySongs by viewModel.librarySongs.collectAsState()
    val libraryAlbums by viewModel.libraryAlbums.collectAsState()
    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)
    val lazyListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showLocal by rememberSaveable(viewModel.artistId) { mutableStateOf(false) }
    val systemBarsTopPadding = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val surfaceColor = if (StandardChrome.isDark) Color(0xFF090909) else StandardChrome.background
    val thumbnail = artistPage?.artist?.thumbnail ?: libraryArtist?.artist?.thumbnailUrl
    val artistName = artistPage?.artist?.title ?: libraryArtist?.artist?.name
    val remoteLoading = artistPage == null && viewModel.isLoading && !showLocal
    val density = LocalDensity.current

    val latestRelease =
        remember(showLocal, artistPage, libraryAlbums) {
            if (showLocal) {
                libraryAlbums
                    .maxByOrNull { it.album.year ?: Int.MIN_VALUE }
                    ?.let { album ->
                        ArtistLatestRelease(
                            id = album.id,
                            title = album.album.title,
                            thumbnailUrl = album.album.thumbnailUrl,
                            year = album.album.year,
                        )
                    }
            } else {
                artistPage
                    ?.sections
                    .orEmpty()
                    .asSequence()
                    .flatMap { it.items.asSequence() }
                    .filterIsInstance<AlbumItem>()
                    .maxByOrNull { it.year ?: Int.MIN_VALUE }
                    ?.let { album ->
                        ArtistLatestRelease(
                            id = album.id,
                            title = album.title,
                            thumbnailUrl = album.thumbnail,
                            year = album.year,
                        )
                    }
            }
        }

    /*
     * Collapse the artwork toolbar when the portrait itself has left it, not when the entire
     * hero item (title + actions included) finally leaves the LazyColumn. That makes the compact
     * artist name appear immediately after the avatar passes behind the top bar.
     */
    val transparentAppBar by remember(lazyListState, density, systemBarsTopPadding) {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex != 0) {
                false
            } else {
                val viewportWidthPx = lazyListState.layoutInfo.viewportSize.width
                if (viewportWidthPx <= 0) {
                    true
                } else {
                    val artworkHeightPx =
                        with(density) {
                            artistHeroArtworkHeight(viewportWidthPx.toDp()).roundToPx()
                        }
                    val toolbarBottomPx =
                        with(density) {
                            (systemBarsTopPadding + ArtistToolbarArtworkHeight).roundToPx()
                        }
                    val collapseAtPx = (artworkHeightPx - toolbarBottomPx).coerceAtLeast(0)
                    lazyListState.firstVisibleItemScrollOffset < collapseAtPx
                }
            }
        }
    }

    LaunchedEffect(libraryArtist?.artist?.isLocal) {
        showLocal = libraryArtist?.artist?.isLocal == true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceColor)
    ) {
        CompositionLocalProvider(
            LocalSongListVisuals provides
                SongListVisuals(
                    rowHeight = ArtistTrackRowHeight,
                    thumbnailSize = ArtistTrackArtworkSize,
                    thumbnailCornerRadius = ArtistTrackArtworkCornerRadius,
                ),
        ) {
            LazyColumn(
                state = lazyListState,
                contentPadding =
                    LocalPlayerAwareWindowInsets.current
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                        .asPaddingValues(),
            ) {
            // Loading and loaded data share the same reference composition.
            item(key = "header") {
                ArtistHero(
                    name = artistName ?: if (remoteLoading) "" else stringResource(R.string.unknown_artist),
                    thumbnailUrl = thumbnail?.artistPortraitUrl(),
                    background = surfaceColor,
                    subscribed = libraryArtist?.artist?.bookmarkedAt != null,
                    canSubscribe = libraryArtist != null || artistPage != null,
                    canShuffle = if (showLocal) librarySongs.isNotEmpty() else artistPage?.artist?.shuffleEndpoint != null,
                    showRadio = !showLocal,
                    canRadio = artistPage?.artist?.radioEndpoint != null,
                    loading = remoteLoading && libraryArtist == null,
                    topSafePadding = systemBarsTopPadding,
                    onSubscribe = {
                        val artist = libraryArtist?.artist ?: artistPage?.artist?.let { remote ->
                            ArtistEntity(
                                id = remote.id,
                                name = remote.title,
                                channelId = remote.channelId,
                                thumbnailUrl = remote.thumbnail,
                            )
                        }
                        artist?.let {
                            database.setArtistSubscribed(it, libraryArtist?.artist?.bookmarkedAt == null)
                        }
                    },
                    onShuffle = {
                        if (showLocal && librarySongs.isNotEmpty()) {
                            playerConnection.playQueue(ListQueue(
                                title = artistName.orEmpty(),
                                items = librarySongs.shuffled().map { it.toMediaItem() },
                            ))
                        } else if (!showLocal) {
                            artistPage?.artist?.shuffleEndpoint?.let {
                                playerConnection.playQueue(YouTubeQueue(it))
                            }
                        }
                    },
                    onRadio = {
                        artistPage?.artist?.radioEndpoint?.let {
                            playerConnection.playQueue(YouTubeQueue(it))
                        }
                    },
                )
            }
            if (remoteLoading) {
                item(key = "loading-songs") {
                    ShimmerHost { repeat(5) { ListItemPlaceHolder() } }
                }
            } else if (artistPage == null && !showLocal) {
                item(key = "retry-artist") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.artist_load_failed), color = StandardChrome.muted)
                        FilledTonalButton(onClick = viewModel::fetchArtistsFromYTM) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            } else {
                val description = artistPage?.description
                if (!showLocal && !description.isNullOrBlank()) {
                    item(key = "description") {
                        var expanded by rememberSaveable(viewModel.artistId) { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = StandardChrome.muted,
                                maxLines = if (expanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                                Text(stringResource(if (expanded) R.string.show_less else R.string.show_more), color = StandardChrome.text)
                            }
                        }
                    }
                }
                latestRelease?.let { release ->
                    item(
                        key = "latest_release_${release.id}",
                    ) {
                        ArtistLatestReleaseCard(
                            release = release,
                            onClick = {
                                navController.navigate("album/${release.id}")
                            },
                        )
                    }
                }

                // Content sections
                if (showLocal) {
                    // Local Songs Section
                    if (librarySongs.isNotEmpty()) {
                        item {
                            NavigationTitle(
                                title = stringResource(R.string.songs),
                                onClick = {
                                    navController.navigate("artist/${viewModel.artistId}/songs")
                                }
                            )
                        }

                        val filteredLibrarySongs = if (hideExplicit) {
                            librarySongs.filter { !it.song.explicit }
                        } else {
                            librarySongs
                        }

                        itemsIndexed(
                            items = filteredLibrarySongs.take(5),
                            key = { index, item -> "local_song_${item.id}_$index" }
                        ) { index, song ->
                            SongListItem(
                                song = song,
                                showInLibraryIcon = true,
                                isActive = song.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                        onLongClick = {},
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.more_vert),
                                            contentDescription = null,
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (song.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = libraryArtist?.artist?.name ?: "Unknown Artist",
                                                        items = filteredLibrarySongs.map { it.toMediaItem() },
                                                        startIndex = index
                                                    )
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    )
                                    .animateItem(),
                            )
                        }

                        // Show "View All" if more songs available
                        if (filteredLibrarySongs.size > 5) {
                            item {
                                Surface(
                                    onClick = {
                                        navController.navigate("artist/${viewModel.artistId}/songs")
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.view_all),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // Local Albums Section
                    if (libraryAlbums.isNotEmpty()) {
                        item {
                            NavigationTitle(
                                title = stringResource(R.string.albums),
                                onClick = {
                                    navController.navigate("artist/${viewModel.artistId}/albums")
                                }
                            )
                        }

                        item {
                            val filteredLibraryAlbums = if (hideExplicit) {
                                libraryAlbums.filter { !it.album.explicit }
                            } else {
                                libraryAlbums
                            }

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(
                                    items = filteredLibraryAlbums,
                                    key = { album -> "local_album_${album.id}_${filteredLibraryAlbums.indexOf(album)}" }
                                ) { album ->
                                    AlbumGridItem(
                                        album = album,
                                        isActive = mediaMetadata?.album?.id == album.id,
                                        isPlaying = isPlaying,
                                        coroutineScope = coroutineScope,
                                        modifier = Modifier
                                            .combinedClickable(
                                                onClick = {
                                                    navController.navigate("album/${album.id}")
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    menuState.show {
                                                        AlbumMenu(
                                                            originalAlbum = album,
                                                            navController = navController,
                                                            onDismiss = menuState::dismiss
                                                        )
                                                    }
                                                }
                                            )
                                            .animateItem()
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // YouTube/Remote content sections
                    artistPage?.sections?.fastForEach { section ->
                        if (section.items.isNotEmpty()) {
                            item {
                                NavigationTitle(
                                    title = section.title,
                                    onClick = section.moreEndpoint?.let {
                                        {
                                            navController.navigate(
                                                "artist/${viewModel.artistId}/items?browseId=${it.browseId}&params=${it.params}",
                                            )
                                        }
                                    },
                                )
                            }
                        }

                        if ((section.items.firstOrNull() as? SongItem)?.album != null) {
                            // Song items with album info - display as list
                            items(
                                items = section.items.distinctBy { it.id },
                                key = { "youtube_song_${it.id}" },
                            ) { song ->
                                YouTubeListItem(
                                    item = song as SongItem,
                                    isActive = mediaMetadata?.id == song.id,
                                    isPlaying = isPlaying,
                                    trailingContent = {
                                        IconButton(
                                            onClick = {
                                                menuState.show {
                                                    YouTubeSongMenu(
                                                        song = song,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss,
                                                    )
                                                }
                                            },
                                            onLongClick = {},
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.more_vert),
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = {
                                                if (song.id == mediaMetadata?.id) {
                                                    playerConnection.player.togglePlayPause()
                                                } else {
                                                    playerConnection.playQueue(
                                                        YouTubeQueue(
                                                            WatchEndpoint(videoId = song.id),
                                                            song.toMediaMetadata()
                                                        ),
                                                    )
                                                }
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                menuState.show {
                                                    YouTubeSongMenu(
                                                        song = song,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss,
                                                    )
                                                }
                                            },
                                        )
                                        .animateItem(),
                                )
                            }
                        } else {
                            // Grid items (albums, playlists, etc.)
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(
                                        items = section.items.distinctBy { it.id },
                                        key = {
                                            val type = when (it) {
                                                is SongItem -> "song"
                                                is AlbumItem -> "album"
                                                is ArtistItem -> "artist"
                                                is PlaylistItem -> "playlist"
                                                else -> "item"
                                            }
                                            "youtube_${type}_${it.id}"
                                        },
                                    ) { item ->
                                        YouTubeGridItem(
                                            item = item,
                                            isActive = when (item) {
                                                is SongItem -> mediaMetadata?.id == item.id
                                                is AlbumItem -> mediaMetadata?.album?.id == item.id
                                                else -> false
                                            },
                                            isPlaying = isPlaying,
                                            coroutineScope = coroutineScope,
                                            modifier = Modifier
                                                .combinedClickable(
                                                    onClick = {
                                                        when (item) {
                                                            is SongItem ->
                                                                playerConnection.playQueue(
                                                                    YouTubeQueue(
                                                                        WatchEndpoint(videoId = item.id),
                                                                        item.toMediaMetadata()
                                                                    ),
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
                                                    },
                                                )
                                                .animateItem(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom spacing
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
        }

        // FAB for switching between local/remote view
        HideOnScrollFAB(
            visible = !transparentAppBar && librarySongs.isNotEmpty() && libraryArtist?.artist?.isLocal != true,
            lazyListState = lazyListState,
            icon = if (showLocal) R.drawable.language else R.drawable.library_music,
            onClick = {
                showLocal = showLocal.not()
                if (!showLocal && artistPage == null) viewModel.fetchArtistsFromYTM()
            }
        )

        // Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }

    ArtistToolbar(
        name = artistName.orEmpty(),
        overArtwork = transparentAppBar,
        canShare = artistPage != null,
        onBack = navController::navigateUp,
        onBackLongClick = navController::backToMain,
        onCopyLink = {
            viewModel.artistPage?.artist?.shareLink?.let { link ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Artist Link", link)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
            }
        },
        onShare = {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    viewModel.artistPage?.artist?.shareLink
                        ?: "https://music.youtube.com/channel/${viewModel.artistId}"
                )
            }
            context.startActivity(Intent.createChooser(shareIntent, null))
        },
    )


@Composable
private fun ArtistLatestReleaseCard(
    release: ArtistLatestRelease,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!release.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = release.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(ArtistLatestReleaseArtworkSize)
                            .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(ArtistLatestReleaseArtworkSize)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.album),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.latest_release).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = release.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                release.year?.let { year ->
                    Text(
                        text = stringResource(R.string.artist_release_metadata, year),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
}
