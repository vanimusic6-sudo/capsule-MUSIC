package com.nikhil.yt.ui.menu

import androidx.compose.foundation.clickable
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
import com.nikhil.yt.ui.component.DefaultDialog
import com.nikhil.yt.ui.component.VeluneLoader

/**
 * Exactly one permanent row in all More menus. The row owns the ONLY
 * confirmation, so callers must provide a real delete operation in onRemove,
 * not open a second dialog. Incomplete downloads retain both resume and
 * delete without adding a permanent duplicate action.
 */
@Composable
internal fun CapsuleDownloadAction(
    state: Int,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean = true,
    hasStoredItems: Boolean = false,
    collectionName: String? = null,
    actionKey: String = collectionName.orEmpty(),
) {
    val active = state == Download.STATE_DOWNLOADING ||
        state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    val completed = state == Download.STATE_COMPLETED
    var showChoices by remember(actionKey) { mutableStateOf(false) }
    var confirmRemoval by remember(actionKey) { mutableStateOf(false) }

    // A stopped/partial collection needs a choice, not two stacked dialogs.
    if (showChoices) {
        DefaultDialog(
            onDismiss = { showChoices = false },
            title = { Text(stringResource(R.string.action_download)) },
            content = { Text(stringResource(R.string.remove_download)) },
            buttons = {
                TextButton(onClick = { showChoices = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = {
                    showChoices = false
                    onDownload()
                }) { Text(stringResource(R.string.action_download)) }
                TextButton(onClick = {
                    showChoices = false
                    confirmRemoval = true
                }) {
                    Text(stringResource(R.string.remove_download), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
    if (confirmRemoval) {
        DefaultDialog(
            onDismiss = { confirmRemoval = false },
            title = { Text(stringResource(R.string.remove_download)) },
            content = {
                Text(
                    if (collectionName.isNullOrBlank()) {
                        stringResource(R.string.capsule_stop_and_delete_download_message)
                    } else {
                        stringResource(R.string.remove_download_playlist_confirm, collectionName)
                    }
                )
            },
            buttons = {
                TextButton(onClick = { confirmRemoval = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = {
                    confirmRemoval = false
                    onRemove()
                }) { Text(stringResource(R.string.remove_download), color = MaterialTheme.colorScheme.error) }
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
                    painter = painterResource(if (completed) R.drawable.offline else R.drawable.download),
                    contentDescription = null,
                    tint = if (completed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(enabled = enabled || active || completed || hasStoredItems) {
            when {
                completed -> confirmRemoval = true
                active -> onStop()
                hasStoredItems -> showChoices = true
                else -> onDownload()
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
