package com.nikhil.yt.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SoundCloudNativeResults(
    query: String,
    selectedUrl: String?,
    playing: Boolean,
    loadingTrack: Boolean,
    onTrackClick: (SoundCloudCatalog.Track, List<SoundCloudCatalog.Track>) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    var result by remember(query) { mutableStateOf<SoundCloudCatalog.Result<SoundCloudCatalog.SearchPage>?>(null) }
    LaunchedEffect(query) {
        result = null
        result = withContext(Dispatchers.IO) { SoundCloudCatalog.search(query) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.capsule_soundcloud_badge))
        when (val value = result) {
            null -> Status(R.string.capsule_soundcloud_loading)
            SoundCloudCatalog.Result.RateLimited -> Status(R.string.capsule_soundcloud_rate_limited)
            SoundCloudCatalog.Result.Unavailable -> Status(R.string.capsule_soundcloud_request_failed)
            is SoundCloudCatalog.Result.Success -> {
                val page = value.value
                page.tracks.forEach { track ->
                    val selected = track.permalink == selectedUrl
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackClick(track, page.tracks) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            )
                            Text(
                                text = track.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(enabled = track.uploaderUrl != null) {
                                    track.uploaderUrl?.let(onArtistClick)
                                },
                            )
                        }
                        Text(
                            text = stringResource(R.string.capsule_soundcloud_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (selected) {
                            Text(
                                text = stringResource(
                                    when {
                                        loadingTrack -> R.string.capsule_soundcloud_loading
                                        playing -> R.string.capsule_soundcloud_pause
                                        else -> R.string.capsule_soundcloud_play
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                if (page.playlists.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.capsule_soundcloud_playlists))
                    page.playlists.forEach { playlist ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPlaylistClick(playlist.url) }
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AsyncImage(
                                model = playlist.artworkUrl,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = playlist.uploader,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(stringResource(R.string.capsule_soundcloud_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                if (page.users.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.capsule_soundcloud_accounts))
                    page.users.forEach { user ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onUserClick(user.url) }
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AsyncImage(
                                model = user.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(user.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = stringResource(R.string.capsule_soundcloud_followers, user.followerCount.coerceAtLeast(0)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(stringResource(R.string.capsule_soundcloud_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                if (page.tracks.isEmpty() && page.playlists.isEmpty() && page.users.isEmpty()) {
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
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Status(message: Int) {
    Text(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
