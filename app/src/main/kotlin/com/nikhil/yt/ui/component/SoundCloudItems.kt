package com.nikhil.yt.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.media3.exoplayer.offline.Download
import com.nikhil.yt.LocalDownloadUtil
import com.nikhil.yt.R
import com.nikhil.yt.constants.ListThumbnailSize
import com.nikhil.yt.constants.ThumbnailCornerRadius
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.soundcloud.soundCloudMediaId
import com.nikhil.yt.utils.makeTimeString

@Composable
internal fun SoundCloudSourceIcon(
    modifier: Modifier = Modifier,
) {
    MaterialIcon(
        painter = painterResource(R.drawable.soundcloud_source),
        contentDescription = "SoundCloud",
        tint = Color.Unspecified,
        modifier = modifier.size(14.dp),
    )
}

@Composable
private fun SoundCloudDownloadState(
    state: Int?,
) {
    when (state) {
        Download.STATE_COMPLETED -> {
            MaterialIcon(
                painter = painterResource(R.drawable.offline),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        }

        Download.STATE_QUEUED,
        Download.STATE_DOWNLOADING -> {
            VeluneLoader(
                size = 14.dp,
                modifier = Modifier,
            )
            Spacer(Modifier.width(4.dp))
        }
    }
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
    val downloadUtil = LocalDownloadUtil.current
    val mediaId = soundCloudMediaId(track.permalink)
    val download by downloadUtil
        .getDownload(mediaId)
        .collectAsState(initial = null)
    val pendingSoundCloud by downloadUtil.soundCloudPending.collectAsState()
    val downloadState =
        if (mediaId in pendingSoundCloud) Download.STATE_QUEUED else download?.state

    ListItem(
        title = track.title,
        subtitle = {
            SoundCloudDownloadState(downloadState)
            Text(
                text = track.artist,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable(enabled = track.uploaderUrl != null) {
                        track.uploaderUrl?.let(onArtistClick)
                    },
            )
            Spacer(Modifier.width(5.dp))
            SoundCloudSourceIcon()
            if (track.durationSeconds > 0L) {
                Text(
                    text = " • " + makeTimeString(track.durationSeconds * 1000L),
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
            }
        },
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = track.artworkUrl,
                isActive = isActive,
                isPlaying = isPlaying,
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        trailingContent = {
            IconButton(onClick = onMoreClick) {
                MaterialIcon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = null,
                )
            }
        },
        isActive = isActive,
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onMoreClick,
        ),
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
            Text(
                text = playlist.uploader,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(5.dp))
            SoundCloudSourceIcon()
            if (playlist.trackCount > 0L) {
                Text(
                    text = " • " + playlist.trackCount,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
            }
        },
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = playlist.artworkUrl,
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onClick,
        ),
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
            Text(
                text = followerText,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(5.dp))
            SoundCloudSourceIcon()
        },
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = user.avatarUrl,
                isActive = false,
                isPlaying = false,
                shape = CircleShape,
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onClick,
        ),
    )
}
