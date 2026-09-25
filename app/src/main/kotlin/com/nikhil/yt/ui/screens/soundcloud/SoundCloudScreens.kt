package com.nikhil.yt.ui.screens.soundcloud

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalDownloadUtil
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.playback.queues.SoundCloudQueue
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.soundcloud.soundCloudMediaId
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.SoundCloudPlaylistListItem
import com.nikhil.yt.ui.component.SoundCloudSourceIcon
import com.nikhil.yt.ui.component.SoundCloudTrackListItem
import com.nikhil.yt.ui.menu.SoundCloudTrackMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

private fun NavController.openSoundCloudProfile(url: String) {
    navigate("soundcloud/profile?url=" + Uri.encode(url))
}

private fun NavController.openSoundCloudPlaylist(url: String) {
    navigate("soundcloud/playlist?url=" + Uri.encode(url))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCloudProfileScreen(
    url: String,
    navController: NavController,
) {
    var result by remember(url) {
        mutableStateOf<SoundCloudCatalog.Result<SoundCloudCatalog.Profile>?>(null)
    }
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()
    val mediaMetadata = playerConnection?.mediaMetadata?.collectAsState()?.value
    val isPlaying = playerConnection?.isPlaying?.collectAsState()?.value == true

    LaunchedEffect(url) {
        val loaded = runInterruptible(Dispatchers.IO) {
            SoundCloudCatalog.profile(url)
        }
        result =
            if (loaded is SoundCloudCatalog.Result.Success) {
                SoundCloudCatalog.Result.Success(
                    loaded.value.copy(
                        tracks = SoundCloudCatalog.validateTracks(
                            loaded.value.tracks,
                        ),
                    ),
                )
            } else {
                loaded
            }
    }

    Column {
        TopAppBar(
            title = {
                Text(text = stringResource(R.string.capsule_soundcloud_account_title))
            },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )

        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            when (val value = result) {
                null -> {
                    item { StatusText(R.string.capsule_soundcloud_loading) }
                }

                SoundCloudCatalog.Result.RateLimited -> {
                    item { StatusText(R.string.capsule_soundcloud_rate_limited) }
                }

                SoundCloudCatalog.Result.Unavailable -> {
                    item { StatusText(R.string.capsule_soundcloud_request_failed) }
                }

                is SoundCloudCatalog.Result.Success -> {
                    val profile = value.value

                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AsyncImage(
                                model = profile.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(112.dp)
                                    .clip(RoundedCornerShape(56.dp)),
                            )
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.capsule_soundcloud_followers,
                                        profile.followerCount.coerceAtLeast(0),
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.size(6.dp))
                                SoundCloudSourceIcon()
                            }
                            if (profile.description.isNotBlank()) {
                                Text(
                                    text = profile.description,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }

                    if (profile.tracks.isNotEmpty()) {
                        item {
                            SectionHeader(
                                stringResource(R.string.capsule_soundcloud_tracks),
                            )
                        }
                    }

                    items(
                        items = profile.tracks,
                        key = { "sc-profile-track-" + it.permalink },
                    ) { track ->
                        val mediaId = soundCloudMediaId(track.permalink)
                        SoundCloudTrackListItem(
                            track = track,
                            isActive = mediaMetadata?.id == mediaId,
                            isPlaying = isPlaying && mediaMetadata?.id == mediaId,
                            onClick = {
                                SoundCloudQueue.create(
                                    title = profile.name,
                                    tracks = profile.tracks,
                                    requestedStartUrl = track.permalink,
                                    downloads = downloads,
                                )?.let { queue ->
                                    playerConnection?.playQueue(queue)
                                }
                            },
                            onArtistClick = navController::openSoundCloudProfile,
                            onMoreClick = {
                                menuState.show {
                                    SoundCloudTrackMenu(
                                        track = track,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        )
                    }

                    if (profile.playlists.isNotEmpty()) {
                        item {
                            SectionHeader(
                                stringResource(R.string.capsule_soundcloud_playlists),
                            )
                        }
                    }

                    items(
                        items = profile.playlists,
                        key = { "sc-profile-playlist-" + it.url },
                    ) { playlist ->
                        SoundCloudPlaylistListItem(
                            playlist = playlist,
                            onClick = {
                                navController.openSoundCloudPlaylist(playlist.url)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCloudPlaylistScreen(
    url: String,
    navController: NavController,
) {
    var result by remember(url) {
        mutableStateOf<SoundCloudCatalog.Result<SoundCloudCatalog.PlaylistDetails>?>(null)
    }
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()
    val mediaMetadata = playerConnection?.mediaMetadata?.collectAsState()?.value
    val isPlaying = playerConnection?.isPlaying?.collectAsState()?.value == true

    LaunchedEffect(url) {
        result = null
        val initialRaw = runInterruptible(Dispatchers.IO) {
            SoundCloudCatalog.playlist(url)
        }
        val initial =
            if (initialRaw is SoundCloudCatalog.Result.Success) {
                SoundCloudCatalog.Result.Success(
                    initialRaw.value.copy(
                        tracks = SoundCloudCatalog.validateTracks(
                            initialRaw.value.tracks,
                        ),
                    ),
                )
            } else {
                initialRaw
            }
        result = initial

        if (initial is SoundCloudCatalog.Result.Success) {
            var page = initial.value
            var continuation = page.continuation
            var pagesLoaded = 0

            while (page.tracks.size < 100 && pagesLoaded < 7) {
                val request = continuation ?: break
                val more = runInterruptible(Dispatchers.IO) {
                    SoundCloudCatalog.playlistMore(url, request)
                }
                if (more !is SoundCloudCatalog.Result.Success) break

                val chunk = more.value
                val playableChunk = SoundCloudCatalog.validateTracks(
                    chunk.tracks,
                )
                if (chunk.tracks.isEmpty() && chunk.continuation == null) break

                page = page.copy(
                    tracks = (page.tracks + playableChunk)
                        .distinctBy { it.permalink }
                        .take(100),
                    continuation = chunk.continuation,
                )
                result = SoundCloudCatalog.Result.Success(page)
                continuation = chunk.continuation
                pagesLoaded++
            }
        }
    }

    Column {
        TopAppBar(
            title = {
                Text(text = stringResource(R.string.capsule_soundcloud_playlist_title))
            },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )

        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            when (val value = result) {
                null -> {
                    item { StatusText(R.string.capsule_soundcloud_loading) }
                }

                SoundCloudCatalog.Result.RateLimited -> {
                    item { StatusText(R.string.capsule_soundcloud_rate_limited) }
                }

                SoundCloudCatalog.Result.Unavailable -> {
                    item { StatusText(R.string.capsule_soundcloud_request_failed) }
                }

                is SoundCloudCatalog.Result.Success -> {
                    val playlist = value.value

                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AsyncImage(
                                model = playlist.artworkUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                            )
                            Text(
                                text = playlist.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable(enabled = playlist.uploaderUrl != null) {
                                        playlist.uploaderUrl?.let(
                                            navController::openSoundCloudProfile,
                                        )
                                    }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    text = playlist.uploader,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(6.dp))
                                SoundCloudSourceIcon()
                            }
                            Text(
                                text = stringResource(
                                    R.string.capsule_soundcloud_playable_tracks,
                                    playlist.tracks.size,
                                    playlist.trackCount.coerceAtLeast(0),
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                enabled = playlist.tracks.isNotEmpty() && playerConnection != null,
                                onClick = {
                                    SoundCloudQueue.create(
                                        title = playlist.title,
                                        tracks = playlist.tracks,
                                        requestedStartUrl = null,
                                        downloads = downloads,
                                    )?.let { queue ->
                                        playerConnection?.playQueue(queue)
                                    }
                                },
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.capsule_soundcloud_play_all),
                                )
                            }
                        }
                    }

                    items(
                        items = playlist.tracks,
                        key = { "sc-playlist-track-" + it.permalink },
                    ) { track ->
                        val mediaId = soundCloudMediaId(track.permalink)
                        SoundCloudTrackListItem(
                            track = track,
                            isActive = mediaMetadata?.id == mediaId,
                            isPlaying = isPlaying && mediaMetadata?.id == mediaId,
                            onClick = {
                                SoundCloudQueue.create(
                                    title = playlist.title,
                                    tracks = playlist.tracks,
                                    requestedStartUrl = track.permalink,
                                    downloads = downloads,
                                )?.let { queue ->
                                    playerConnection?.playQueue(queue)
                                }
                            },
                            onArtistClick = navController::openSoundCloudProfile,
                            onMoreClick = {
                                menuState.show {
                                    SoundCloudTrackMenu(
                                        track = track,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        )
                    }

                    if (playlist.tracks.isEmpty()) {
                        item {
                            StatusText(
                                R.string.capsule_soundcloud_no_playable_tracks,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(
            horizontal = 20.dp,
            vertical = 12.dp,
        ),
    )
}

@Composable
private fun StatusText(
    resourceId: Int,
) {
    Text(
        text = stringResource(resourceId),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(20.dp),
    )
}
