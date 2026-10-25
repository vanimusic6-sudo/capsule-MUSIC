package com.nikhil.yt.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
    // Player More must remain visible when an audio download starts.
    keepOpenOnDownload: Boolean = false,
    // The player menu has no destructive X for an active transfer.
    showActiveStopAction: Boolean = true,
) {
    val context = LocalContext.current
    var confirmPartialDelete by remember(mediaId) { mutableStateOf(false) }
    var waitingForIndex by remember(mediaId) { mutableStateOf(false) }
    // Media3 changes download state asynchronously. Start the comet on tap,
    // not seconds later when DownloadManager finally publishes its state.
    LaunchedEffect(waitingForIndex, download?.visibleState) {
        if (!waitingForIndex) return@LaunchedEffect
        if (download?.visibleState in listOf(
                Download.STATE_QUEUED, Download.STATE_DOWNLOADING,
                Download.STATE_RESTARTING, Download.STATE_COMPLETED,
            )) waitingForIndex = false
        else { delay(6_000); waitingForIndex = false }
    }

    @Composable
    fun Action(label: Int, icon: Int, destructive: Boolean = false,
        busy: Boolean = false, closeMenu: Boolean = true, click: () -> Unit) {
        ListItem(
            headlineContent = {
                Text(stringResource(label),
                    color = if (destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface)
            },
            leadingContent = {
                if (busy) CapsuleDownloadComet()
                else Icon(painterResource(icon), contentDescription = null,
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
                Action(R.string.remove_download, R.drawable.offline,
                    destructive = true, closeMenu = !keepOpenOnDownload) {
                    DownloadService.sendRemoveDownload(
                        context, ExoDownloadService::class.java, mediaId, false)
                }
            }
            Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> {
                val entry = download ?: return@Column
                if (entry.canPrioritize) {
                    Action(R.string.download_now, R.drawable.download,
                        busy = waitingForIndex, closeMenu = !keepOpenOnDownload) {
                        waitingForIndex = true
                        enqueuePriorityAudioDownload(
                            context, ExoDownloadService::class.java, entry.request, false)
                    }
                } else if (showActiveStopAction) {
                    Action(R.string.capsule_stop_and_delete_download, R.drawable.close,
                        destructive = true, closeMenu = false) {
                        confirmPartialDelete = true
                    }
                } else {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.downloading)) },
                        leadingContent = { CapsuleDownloadComet() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            else -> {
                Action(if (waitingForIndex) R.string.downloading else R.string.action_download,
                    R.drawable.download, busy = waitingForIndex,
                    closeMenu = !keepOpenOnDownload) {
                    waitingForIndex = true
                    if (download == null) onStart()
                    else enqueuePriorityAudioDownload(
                        context, ExoDownloadService::class.java, download.request, false)
                }
                if (download != null) {
                    Action(R.string.remove_download, R.drawable.offline,
                        destructive = true, closeMenu = !keepOpenOnDownload) {
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
