package com.nikhil.yt.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.nikhil.yt.R
import com.nikhil.yt.playback.ExoDownloadService
import com.nikhil.yt.playback.canPrioritize
import com.nikhil.yt.playback.enqueuePriorityAudioDownload
import com.nikhil.yt.playback.visibleState

/**
 * Keep all YouTube download entry points consistent. A paused/failed item
 * retains cached bytes and MUST offer both resume and permanent removal.
 */
@Composable
internal fun YouTubeDownloadMenuItems(
    download: Download?,
    mediaId: String,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var confirmPartialDelete by remember(mediaId) { mutableStateOf(false) }

    @Composable
    fun Action(label: Int, icon: Int, destructive: Boolean = false, closeMenu: Boolean = true, click: () -> Unit) {
        ListItem(
            headlineContent = {
                Text(stringResource(label),
                    color = if (destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface)
            },
            leadingContent = {
                Icon(painterResource(icon), contentDescription = null,
                    tint = if (destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.clickable {
                click()
                if (closeMenu) onDismiss()
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    Column {
        when (download?.visibleState) {
            Download.STATE_COMPLETED -> {
                Action(R.string.remove_download, R.drawable.offline, destructive = true) {
                    DownloadService.sendRemoveDownload(
                        context, ExoDownloadService::class.java, mediaId, false)
                }
            }
            Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> {
                val entry = download ?: return@Column
                if (entry.canPrioritize) {
                    Action(R.string.download_now, R.drawable.download) {
                        enqueuePriorityAudioDownload(
                            context, ExoDownloadService::class.java, entry.request, false)
                    }
                } else {
                    Action(R.string.capsule_stop_and_delete_download, R.drawable.close,
                        destructive = true, closeMenu = false) {
                        confirmPartialDelete = true
                    }
                }
            }
            else -> {
                Action(R.string.action_download, R.drawable.download) {
                    if (download == null) onStart()
                    else enqueuePriorityAudioDownload(
                        context, ExoDownloadService::class.java, download.request, false)
                }
                if (download != null) {
                    Action(R.string.remove_download, R.drawable.offline, destructive = true) {
                        DownloadService.sendRemoveDownload(
                            context, ExoDownloadService::class.java, mediaId, false)
                    }
                }
            }
        }
    }
    if (confirmPartialDelete) {
        DownloadRemovalDialog(
            onDismiss = { confirmPartialDelete = false },
            onConfirm = {
                confirmPartialDelete = false
                DownloadService.sendRemoveDownload(
                    context, ExoDownloadService::class.java, mediaId, false)
                onDismiss()
            },
        )
    }
}
