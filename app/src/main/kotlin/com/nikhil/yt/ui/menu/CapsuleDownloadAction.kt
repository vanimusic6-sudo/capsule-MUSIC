package com.nikhil.yt.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
    hasCompletedItems: Boolean = false,
    collectionName: String? = null,
    isCollection: Boolean = true,
    // Player's single-track download is an intentionally fast exception:
    // second tap cancels + discards, completed tap removes immediately.
    instantRemove: Boolean = false,
    actionKey: String = collectionName.orEmpty(),
) {
    val active = state == Download.STATE_DOWNLOADING ||
        state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    val completed = state == Download.STATE_COMPLETED
    val removing = state == Download.STATE_REMOVING
    // Media3 does not update its index in the same frame as the first tap.
    // Keep a short local pending state so a quick second tap opens the
    // pause/delete menu rather than submitting the same ADD twice.
    var pendingStart by remember(actionKey) { mutableStateOf(false) }
    LaunchedEffect(pendingStart, state) {
        if (pendingStart) {
            if (active || completed || removing) pendingStart = false
            else {
                delay(2_000L)
                pendingStart = false
            }
        }
    }
    val currentlyDownloading = active || pendingStart
    val idleDownloadTitle = when {
        hasCompletedItems && !completed -> R.string.capsule_download_remaining
        hasStoredItems && !completed -> R.string.capsule_download_resume_partial
        else -> R.string.action_download
    }
    var showChoices by remember(actionKey) { mutableStateOf(false) }
    var choiceOpenedDuringDownload by remember(actionKey) { mutableStateOf(false) }
    var confirmRemoval by remember(actionKey) { mutableStateOf(false) }

    // A stopped/partial collection needs a choice, not two stacked dialogs.
    if (showChoices) {
        DefaultDialog(
            onDismiss = { showChoices = false },
            title = {
                Text(stringResource(if (choiceOpenedDuringDownload) R.string.downloading
                    else idleDownloadTitle))
            },
            content = {
                ListItem(
                    headlineContent = {
                        Text(stringResource(
                            if (choiceOpenedDuringDownload) R.string.capsule_download_pause_keep
                            else idleDownloadTitle,
                        ))
                    },
                    leadingContent = {
                        Icon(painterResource(if (choiceOpenedDuringDownload) R.drawable.pause
                            else R.drawable.download), contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        showChoices = false
                        if (choiceOpenedDuringDownload) {
                            pendingStart = false
                            onStop()
                        } else {
                            pendingStart = true
                            onDownload()
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(
                                if (choiceOpenedDuringDownload) R.string.capsule_download_stop_delete
                                else R.string.remove_download,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingContent = {
                        Icon(painterResource(R.drawable.offline), contentDescription = null,
                            tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable {
                        // This *is* the requested second-tap confirmation.
                        // Do not open another nested dialog before deleting.
                        showChoices = false
                        pendingStart = false
                        onRemove()
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            },
            buttons = {
                TextButton(onClick = { showChoices = false }) {
                    Text(stringResource(android.R.string.cancel))
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
                    } else if (isCollection) {
                        stringResource(R.string.remove_download_playlist_confirm, collectionName)
                    } else {
                        stringResource(R.string.capsule_download_track_remove_confirm, collectionName)
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
                        completed || removing -> R.string.remove_download
                        currentlyDownloading -> R.string.downloading
                        else -> idleDownloadTitle
                    }
                ),
                color = if (completed || removing) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingContent = {
            when {
                currentlyDownloading || removing -> VeluneLoader(size = 24.dp,
                    color = if (removing) MaterialTheme.colorScheme.error else null)
                else -> Icon(
                    painter = painterResource(if (completed) R.drawable.offline else R.drawable.download),
                    contentDescription = null,
                    tint = if (completed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(enabled = !removing && (enabled || currentlyDownloading || completed || hasStoredItems)) {
            when {
                instantRemove && (completed || currentlyDownloading || hasStoredItems) -> {
                    pendingStart = false
                    onRemove()
                }
                completed -> confirmRemoval = true
                currentlyDownloading -> {
                    choiceOpenedDuringDownload = true
                    showChoices = true
                }
                hasStoredItems -> {
                    choiceOpenedDuringDownload = false
                    showChoices = true
                }
                else -> {
                    pendingStart = true
                    onDownload()
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
