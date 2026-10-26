package com.nikhil.yt.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.VeluneLoader

/**
 * One download action, identical across collections and individual tracks.
 * Stopped/failed collections may have cached bytes but aren't COMPLETE:
 * expose both resume and deletion from the single row, rather than hiding
 * the only Delete operation or adding a second permanent button.
 */
@Composable
internal fun CapsuleDownloadAction(
    state: Int,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean = true,
    hasStoredItems: Boolean = false,
) {
    val active = state == Download.STATE_DOWNLOADING ||
        state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    val completed = state == Download.STATE_COMPLETED
    var showStoppedActions by remember { mutableStateOf(false) }

    if (showStoppedActions) {
        AlertDialog(
            onDismissRequest = { showStoppedActions = false },
            title = { Text(stringResource(R.string.action_download)) },
            text = { Text(stringResource(R.string.remove_download)) },
            confirmButton = {
                TextButton(onClick = {
                    showStoppedActions = false
                    onDownload()
                }) { Text(stringResource(R.string.action_download)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStoppedActions = false
                    onRemove()
                }) {
                    Text(
                        stringResource(R.string.remove_download),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }

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
                    tint = if (completed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(enabled = enabled || active || completed || hasStoredItems) {
            when {
                completed -> onRemove()
                active -> onStop()
                hasStoredItems -> showStoppedActions = true
                else -> onDownload()
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
