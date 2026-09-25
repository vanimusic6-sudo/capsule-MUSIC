package com.nikhil.yt.ui.screens.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.ui.component.SoundCloudPlaylistListItem
import com.nikhil.yt.ui.component.SoundCloudTrackListItem
import com.nikhil.yt.ui.component.SoundCloudUserListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible

internal enum class SoundCloudResultKind {
    ALL,
    TRACKS,
    PLAYLISTS,
    USERS,
}

@Composable
internal fun SoundCloudNativeResults(
    query: String,
    kind: SoundCloudResultKind,
    selectedUrl: String?,
    playing: Boolean,
    onTrackClick: (SoundCloudCatalog.Track, List<SoundCloudCatalog.Track>) -> Unit,
    onTrackMenu: (SoundCloudCatalog.Track) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    var result by remember(query) {
        mutableStateOf<SoundCloudCatalog.Result<SoundCloudCatalog.SearchPage>?>(null)
    }

    LaunchedEffect(query) {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            result = SoundCloudCatalog.Result.Success(
                SoundCloudCatalog.SearchPage(
                    tracks = emptyList(),
                    users = emptyList(),
                    playlists = emptyList(),
                    continuation = null,
                ),
            )
            return@LaunchedEffect
        }

        delay(280L)
        val initial = runInterruptible(Dispatchers.IO) {
            SoundCloudCatalog.search(normalized)
        }
        result = initial

        if (initial is SoundCloudCatalog.Result.Success) {
            var page = initial.value
            var continuation = page.continuation
            var pagesLoaded = 0

            while (
                continuation != null &&
                page.tracks.size < MAX_SEARCH_TRACKS &&
                pagesLoaded < MAX_SEARCH_PAGES
            ) {
                val request = continuation ?: break
                val more = runInterruptible(Dispatchers.IO) {
                    SoundCloudCatalog.searchMore(request)
                }
                if (more !is SoundCloudCatalog.Result.Success) break

                val chunk = more.value
                page = page.copy(
                    tracks = (page.tracks + chunk.tracks)
                        .distinctBy { it.permalink }
                        .take(MAX_SEARCH_TRACKS),
                    users = (page.users + chunk.users)
                        .distinctBy { it.url }
                        .take(MAX_SEARCH_SECONDARY),
                    playlists = (page.playlists + chunk.playlists)
                        .distinctBy { it.url }
                        .take(MAX_SEARCH_SECONDARY),
                    continuation = chunk.continuation,
                )
                result = SoundCloudCatalog.Result.Success(page)
                continuation = chunk.continuation
                pagesLoaded++
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        when (val value = result) {
            null -> Status(R.string.capsule_soundcloud_loading)
            SoundCloudCatalog.Result.RateLimited -> Status(R.string.capsule_soundcloud_rate_limited)
            SoundCloudCatalog.Result.Unavailable -> Status(R.string.capsule_soundcloud_request_failed)

            is SoundCloudCatalog.Result.Success -> {
                val page = value.value

                if (kind == SoundCloudResultKind.ALL || kind == SoundCloudResultKind.TRACKS) {
                    if (page.tracks.isNotEmpty()) {
                        SectionTitle(stringResource(R.string.capsule_soundcloud_tracks))
                    }
                    page.tracks.forEach { track ->
                        SoundCloudTrackListItem(
                            track = track,
                            isActive = track.permalink == selectedUrl,
                            isPlaying = playing && track.permalink == selectedUrl,
                            onClick = { onTrackClick(track, page.tracks) },
                            onArtistClick = onArtistClick,
                            onMoreClick = { onTrackMenu(track) },
                        )
                    }
                }

                if (kind == SoundCloudResultKind.ALL || kind == SoundCloudResultKind.PLAYLISTS) {
                    if (page.playlists.isNotEmpty()) {
                        SectionTitle(stringResource(R.string.capsule_soundcloud_playlists))
                    }
                    page.playlists.forEach { playlist ->
                        SoundCloudPlaylistListItem(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist.url) },
                        )
                    }
                }

                if (kind == SoundCloudResultKind.ALL || kind == SoundCloudResultKind.USERS) {
                    if (page.users.isNotEmpty()) {
                        SectionTitle(stringResource(R.string.capsule_soundcloud_accounts))
                    }
                    page.users.forEach { user ->
                        SoundCloudUserListItem(
                            user = user,
                            followerText = stringResource(
                                R.string.capsule_soundcloud_followers,
                                user.followerCount.coerceAtLeast(0),
                            ),
                            onClick = { onUserClick(user.url) },
                        )
                    }
                }

                val empty = when (kind) {
                    SoundCloudResultKind.ALL ->
                        page.tracks.isEmpty() && page.playlists.isEmpty() && page.users.isEmpty()
                    SoundCloudResultKind.TRACKS -> page.tracks.isEmpty()
                    SoundCloudResultKind.PLAYLISTS -> page.playlists.isEmpty()
                    SoundCloudResultKind.USERS -> page.users.isEmpty()
                }
                if (empty) {
                    Status(R.string.capsule_soundcloud_no_results)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
            start = 20.dp,
            top = 14.dp,
            bottom = 4.dp,
        ),
    )
}

@Composable
private fun Status(message: Int) {
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

private const val MAX_SEARCH_TRACKS = 50
private const val MAX_SEARCH_SECONDARY = 20
private const val MAX_SEARCH_PAGES = 5
