package com.nikhil.yt.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.discovery.ExperimentalDownload

/**
 * Use Capsule's regular 64dp song row, not Material3's roomy generic ListItem.
 * A download checkmark is a secondary badge, never a substitute for album art.
 */
@Composable
fun OfflineSongListItem(
    file: ExperimentalDownload.SavedAudio,
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val visuals = LocalSongListVisuals.current
    val shape = RoundedCornerShape(visuals.thumbnailCornerRadius)
    ListItem(
        title = file.title,
        subtitle = file.artist + " · Audius",
        modifier = modifier,
        isActive = isActive,
        itemHeight = visuals.rowHeight,
        thumbnailContent = {
            if (file.artwork != null) {
                ItemThumbnail(
                    thumbnailUrl = file.artwork,
                    isActive = isActive,
                    isPlaying = isPlaying,
                    shape = shape,
                    modifier = Modifier.size(visuals.thumbnailSize),
                )
            } else {
                // Old downloads might not have a stored cover. Do not display
                // a huge generic download icon in place of their artwork.
                Box(
                    modifier = Modifier.size(visuals.thumbnailSize)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.offline),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(end = 12.dp).size(16.dp),
            )
        },
    )
}
