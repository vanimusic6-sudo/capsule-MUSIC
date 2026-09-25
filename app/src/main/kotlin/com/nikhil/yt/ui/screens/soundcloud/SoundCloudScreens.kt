package com.nikhil.yt.ui.screens.soundcloud

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.playback.queues.SoundCloudQueue
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import androidx.compose.foundation.layout.asPaddingValues

private fun NavController.openSoundCloudProfile(url: String) =
    navigate("soundcloud/profile?url=" + Uri.encode(url))

private fun NavController.openSoundCloudPlaylist(url: String) =
    navigate("soundcloud/playlist?url=" + Uri.encode(url))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCloudProfileScreen(url: String, navController: NavController) {
    var result by remember(url) { mutableStateOf<SoundCloudCatalog.Result<SoundCloudCatalog.Profile>?>(null) }
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    var loadingPlay by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        result = runInterruptible(Dispatchers.IO) { SoundCloudCatalog.profile(url) }
    }

    Column {
        TopAppBar(
            title = { Text(stringResource(R.string.capsule_soundcloud_account_title)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(painterResource(R.drawable.arrow_back), null)
                }
            },
        )
        LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
            when (val value = result) {
                null -> item { StatusText(R.string.capsule_soundcloud_loading) }
                SoundCloudCatalog.Result.RateLimited -> item { StatusText(R.string.capsule_soundcloud_rate_limited) }
                SoundCloudCatalog.Result.Unavailable -> item { StatusText(R.string.capsule_soundcloud_request_failed) }
                is SoundCloudCatalog.Result.Success -> {
                    val p = value.value
                    item {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = p.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier.size(112.dp).clip(RoundedCornerShape(56.dp)),
                            )
                            Text(p.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                            Text(
                                stringResource(R.string.capsule_soundcloud_followers, p.followerCount.coerceAtLeast(0)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (p.description.isNotBlank()) {
                                Text(p.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    if (p.tracks.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.capsule_soundcloud_tracks)) }
                        items(p.tracks.size, key = { "sc-profile-track-" + p.tracks[it].permalink }) { index ->
                            val track = p.tracks[index]
                            TrackRow(
                                track = track,
                                onArtistClick = { artistUrl -> navController.openSoundCloudProfile(artistUrl) },
                                onClick = {
                                if (!loadingPlay && playerConnection != null) {
                                    loadingPlay = true
                                    scope.launch {
                                        val queue = SoundCloudQueue.create(
                                            title = "SoundCloud • " + p.name,
                                            tracks = p.tracks,
                                            requestedStartUrl = track.permalink,
                                        )
                                        if (queue != null) playerConnection.playQueue(queue)
                                        loadingPlay = false
                                    }
                                }
                                },
                            )
                        }
                    }
                    if (p.playlists.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.capsule_soundcloud_playlists)) }
                        items(p.playlists.size, key = { "sc-profile-playlist-" + p.playlists[it].url }) { index ->
                            PlaylistRow(p.playlists[index]) { navController.openSoundCloudPlaylist(it.url) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCloudPlaylistScreen(url: String, navController: NavController) {
    var result by remember(url) { mutableStateOf<SoundCloudCatalog.Result<SoundCloudCatalog.PlaylistDetails>?>(null) }
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    var loadingPlay by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        result = null
        val initial = runInterruptible(Dispatchers.IO) {
            SoundCloudCatalog.playlist(url)
        }
        result = initial

        if (initial is SoundCloudCatalog.Result.Success) {
            var page = initial.value
            var continuation = page.continuation
            var pagesLoaded = 0

            // Page one is already visible. Continue filling the list without
            // making the user wait for the entire 100-track budget up front.
            while (page.tracks.size < 100 && pagesLoaded < 7) {
                val pageRequest = continuation ?: break
                val more = runInterruptible(Dispatchers.IO) {
                    SoundCloudCatalog.playlistMore(url, pageRequest)
                }
                if (more !is SoundCloudCatalog.Result.Success) break

                val chunk = more.value
                if (chunk.tracks.isEmpty()) break

                page = page.copy(
                    tracks = (page.tracks + chunk.tracks)
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
            title = { Text(stringResource(R.string.capsule_soundcloud_playlist_title)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(painterResource(R.drawable.arrow_back), null)
                }
            },
        )
        LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
            when (val value = result) {
                null -> item { StatusText(R.string.capsule_soundcloud_loading) }
                SoundCloudCatalog.Result.RateLimited -> item { StatusText(R.string.capsule_soundcloud_rate_limited) }
                SoundCloudCatalog.Result.Unavailable -> item { StatusText(R.string.capsule_soundcloud_request_failed) }
                is SoundCloudCatalog.Result.Success -> {
                    val p = value.value
                    item {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = p.artworkUrl,
                                contentDescription = null,
                                modifier = Modifier.size(180.dp).clip(RoundedCornerShape(16.dp)),
                            )
                            Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                            Text(
                                p.uploader,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(enabled = p.uploaderUrl != null) {
                                    p.uploaderUrl?.let { artistUrl -> navController.openSoundCloudProfile(artistUrl) }
                                }.padding(vertical = 6.dp),
                            )
                            Text(
                                stringResource(R.string.capsule_soundcloud_playable_tracks, p.tracks.size, p.trackCount.coerceAtLeast(0)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                enabled = p.tracks.isNotEmpty() && !loadingPlay && playerConnection != null,
                                onClick = {
                                    loadingPlay = true
                                    scope.launch {
                                        val queue = SoundCloudQueue.create(
                                            title = "SoundCloud • " + p.title,
                                            tracks = p.tracks,
                                            requestedStartUrl = null,
                                        )
                                        if (queue != null) playerConnection?.playQueue(queue)
                                        loadingPlay = false
                                    }
                                },
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                Text(if (loadingPlay) stringResource(R.string.capsule_soundcloud_loading) else stringResource(R.string.capsule_soundcloud_play_all))
                            }
                        }
                    }
                    items(p.tracks.size, key = { "sc-playlist-track-" + p.tracks[it].permalink }) { index ->
                        val track = p.tracks[index]
                        TrackRow(
                            track = track,
                            onArtistClick = { artistUrl -> navController.openSoundCloudProfile(artistUrl) },
                            onClick = {
                                if (!loadingPlay && playerConnection != null) {
                                    loadingPlay = true
                                    scope.launch {
                                        val queue = SoundCloudQueue.create(
                                            title = "SoundCloud • " + p.title,
                                            tracks = p.tracks,
                                            requestedStartUrl = track.permalink,
                                        )
                                        if (queue != null) playerConnection.playQueue(queue)
                                        loadingPlay = false
                                    }
                                }
                            },
                        )
                    }
                    if (p.tracks.isEmpty()) item { StatusText(R.string.capsule_soundcloud_no_playable_tracks) }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: SoundCloudCatalog.Track,
    onArtistClick: (String) -> Unit = {},
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(track.artworkUrl, null, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = track.uploaderUrl != null) {
                    track.uploaderUrl?.let(onArtistClick)
                },
            )
        }
        Text(stringResource(R.string.capsule_soundcloud_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PlaylistRow(playlist: SoundCloudCatalog.Playlist, onClick: (SoundCloudCatalog.Playlist) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick(playlist) }.padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(playlist.artworkUrl, null, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(playlist.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.capsule_soundcloud_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
private fun StatusText(res: Int) {
    Text(stringResource(res), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
}
