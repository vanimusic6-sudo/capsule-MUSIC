package com.nikhil.yt.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalDownloadUtil
import com.nikhil.yt.R
import com.nikhil.yt.constants.ListThumbnailSize
import com.nikhil.yt.constants.ThumbnailCornerRadius
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.soundcloud.soundCloudMediaId
import com.nikhil.yt.utils.makeTimeString

@Composable
internal fun SoundCloudSourceIcon(modifier: Modifier = Modifier) {
    MaterialIcon(
        painterResource(R.drawable.soundcloud_source), "SoundCloud",
        tint = Color.Unspecified, modifier = modifier.size(14.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SoundCloudTrackListItem(
    track: SoundCloudCatalog.Track,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onArtistClick: (String) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val download by LocalDownloadUtil.current
        .getDownload(soundCloudMediaId(track.permalink)).collectAsState(initial = null)
    ListItem(
        title = track.title,
        subtitle = {
            Icon.Download(download?.state)
            Text(
                track.artist, color = MaterialTheme.colorScheme.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, false).clickable(enabled = track.uploaderUrl != null) {
                    track.uploaderUrl?.let(onArtistClick)
                }
            )
            Spacer(Modifier.width(5.dp))
            SoundCloudSourceIcon()
            if (track.durationSeconds > 0) {
                Text(" • " + makeTimeString(track.durationSeconds * 1000L),
                    color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            }
        },
        thumbnailContent = {
            ItemThumbnail(
                track.artworkUrl, isActive = isActive, isPlaying = isPlaying,
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                modifier = Modifier.size(ListThumbnailSize)
            )
        },
        trailingContent = {
            IconButton(onClick = onMoreClick) {
                MaterialIcon(painterResource(R.drawable.more_vert), null)
            }
        },
        isActive = isActive,
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onMoreClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SoundCloudPlaylistListItem(
    playlist: SoundCloudCatalog.Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        title = playlist.title,
        subtitle = {
            Text(playlist.uploader, color = MaterialTheme.colorScheme.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, false))
            Spacer(Modifier.width(5.dp))
            SoundCloudSourceIcon()
            if (playlist.trackCount > 0) Text(" • " + playlist.trackCount,
                color = MaterialTheme.colorScheme.secondary)
        },
        thumbnailContent = {
            ItemThumbnail(playlist.artworkUrl, shape = RoundedCornerShape(ThumbnailCornerRadius),
                modifier = Modifier.size(ListThumbnailSize))
        },
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SoundCloudUserListItem(
    user: SoundCloudCatalog.User,
    followerText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        title = user.name,
        subtitle = {
            Text(followerText, color = MaterialTheme.colorScheme.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, false))
            Spacer(Modifier.width(5.dp))
            SoundCloudSourceIcon()
        },
        thumbnailContent = {
            ItemThumbnail(user.avatarUrl, shape = CircleShape, modifier = Modifier.size(ListThumbnailSize))
        },
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onClick)
    )
}
