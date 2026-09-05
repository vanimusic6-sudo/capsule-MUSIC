/*
 * Capsule MUSIC
 *
 * Shared components used by the Capsule queue.
 *
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.component.BottomSheetState
import com.nikhil.yt.ui.component.VeluneLoader
import com.nikhil.yt.ui.component.bottomSheetDraggable
import com.nikhil.yt.utils.makeTimeString

/**
 * Current Song Header shown at the top of the queue
 * Displays album art, song info, and control buttons
 */
@Composable
fun CurrentSongHeader(
    sheetState: BottomSheetState,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    repeatMode: Int,
    shuffleModeEnabled: Boolean,
    locked: Boolean,
    songCount: Int,
    queueDuration: Int,
    infiniteQueueEnabled: Boolean,
    automixLoading: Boolean,
    backgroundColor: Color,
    onBackgroundColor: Color,
    onToggleLike: () -> Unit,
    onMenuClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onLockClick: () -> Unit,
    onInfiniteQueueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .bottomSheetDraggable(sheetState)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(onBackgroundColor.copy(alpha = 0.3f))
            )
        }

        // Song info row with thumbnail, title, artist, and action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Album art thumbnail
            AsyncImage(
                model = mediaMetadata?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            // Song title and artist
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = mediaMetadata?.title ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onBackgroundColor
                )
                Text(
                    text = mediaMetadata?.artists?.joinToString(",") { artist -> artist.name } ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onBackgroundColor.copy(alpha = 0.7f)
                )
            }

            // Like button
            IconButton(
                onClick = onToggleLike,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(
                        if (mediaMetadata?.liked == true) R.drawable.favorite
                        else R.drawable.favorite_border
                    ),
                    contentDescription = stringResource(R.string.action_like),
                    tint = if (mediaMetadata?.liked == true)
                        MaterialTheme.colorScheme.primary
                    else onBackgroundColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Lock button
            IconButton(
                onClick = onLockClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(if (locked) R.drawable.lock else R.drawable.lock_open),
                    contentDescription = if (locked) "Unlock mode" else "Lock mode",
                    tint = onBackgroundColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Menu button
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = "Options",
                    tint = onBackgroundColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Control buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shuffle button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (shuffleModeEnabled) onBackgroundColor.copy(alpha = 0.2f)
                        else onBackgroundColor.copy(alpha = 0.15f)
                    )
                    .clickable { onShuffleClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.shuffle),
                    contentDescription = stringResource(R.string.action_shuffle_on),
                    tint = onBackgroundColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Repeat button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (repeatMode != Player.REPEAT_MODE_OFF) onBackgroundColor.copy(alpha = 0.2f)
                        else onBackgroundColor.copy(alpha = 0.15f)
                    )
                    .clickable { onRepeatClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> R.drawable.repeat
                        }
                    ),
                    contentDescription = "Toggle repeat",
                    tint = onBackgroundColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Infinity/Automix button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (infiniteQueueEnabled) MaterialTheme.colorScheme.primary
                        else onBackgroundColor.copy(alpha = 0.15f)
                    )
                    .clickable { onInfiniteQueueClick() },
                contentAlignment = Alignment.Center
            ) {
                if (automixLoading) {
                    VeluneLoader(size = 18.dp)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.all_inclusive),
                        contentDescription = stringResource(R.string.similar_content),
                        tint = if (infiniteQueueEnabled) MaterialTheme.colorScheme.onPrimary
                               else onBackgroundColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // "Continue Playing" text and stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.queue_continue_playing),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = onBackgroundColor
                )
                Text(
                    text = stringResource(R.string.queue_autoplaying_similar),
                    style = MaterialTheme.typography.bodySmall,
                    color = onBackgroundColor.copy(alpha = 0.6f)
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.n_song,
                        songCount,
                        songCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onBackgroundColor.copy(alpha = 0.8f)
                )
                Text(
                    text = makeTimeString(queueDuration * 1000L),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onBackgroundColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}
