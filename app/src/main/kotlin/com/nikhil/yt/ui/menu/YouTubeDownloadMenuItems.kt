package com.nikhil.yt.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.nikhil.yt.playback.ExoDownloadService
import com.nikhil.yt.playback.enqueuePriorityAudioDownload
import com.nikhil.yt.playback.visibleState

/**
 * All YouTube single-track menus, including the player, use exactly the
 * same visible action and icon as local playlists. Download interactions do
 * not dismiss More; the current row updates when Media3 publishes its state.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun YouTubeDownloadMenuItems(
    download: Download?,
    mediaId: String,
    trackTitle: String? = null,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    keepOpenOnDownload: Boolean = false,
    showActiveStopAction: Boolean = true,
    instantRemove: Boolean = true,
) {
    val context = LocalContext.current
    CapsuleDownloadAction(
        actionKey = mediaId,
        collectionName = trackTitle,
        isCollection = false,
        instantRemove = instantRemove,
        hasStoredItems = download != null,
        state = download?.visibleState ?: Download.STATE_STOPPED,
        onDownload = {
            if (download == null) onStart()
            else {
                enqueuePriorityAudioDownload(
                    context, ExoDownloadService::class.java, download.request, false,
                )
                // STOP_REASON_USER survives a DownloadRequest replacement in
                // Media3. Explicitly clear it AFTER the service receives ADD,
                // otherwise "Resume" can silently leave the item stopped.
                DownloadService.sendSetStopReason(
                    context, ExoDownloadService::class.java,
                    mediaId, Download.STOP_REASON_NONE, false,
                )
            }
        },
        onStop = {}, // Single-track taps only start or remove, never open a stop dialog.
        onRemove = {
            DownloadService.sendRemoveDownload(
                context, ExoDownloadService::class.java, mediaId, false,
            )
        },
    )
}
