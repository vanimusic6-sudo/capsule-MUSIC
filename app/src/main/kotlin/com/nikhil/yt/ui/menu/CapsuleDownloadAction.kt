package com.nikhil.yt.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.VeluneLoader

/**
 * The single download row used by local/online playlists and individual songs.
 * Same state labels, same loader and same click semantics everywhere:
 * idle/paused -> download or resume, active -> stop (preserve bytes),
 * complete -> remove. No secondary "cancel and delete" action.
 *
 * Confirmation belongs to the caller because playlists require a named dialog.
 */
@Composable
internal fun CapsuleDownloadAction(
    state: Int,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean = true,
) {
    val active = state == Download.STATE_DOWNLOADING ||
        state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    val completed = state == Download.STATE_COMPLETED
    ListItem(
        headlineContent = {
            Text(
                stringResource(
                    when {
                        completed -> R.string.remove_download
                        active -> R.string.downloading
                        else -> R.string.action_download
                    }
                ),
                color = if (completed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingContent = {
            when {
                active -> VeluneLoader(size = 24.dp)
                else -> Icon(
                    painterResource(
                        if (completed) R.drawable.offline else R.drawable.download
                    ),
                    contentDescription = null,
                )
            }
        },
        modifier = Modifier.clickable(enabled = enabled || active || completed) {
            when {
                completed -> onRemove()
                active -> onStop()
                else -> onDownload()
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
