package com.nikhil.yt.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.nikhil.yt.LocalDownloadUtil
import com.nikhil.yt.playback.COLLECTION_STOP_REASON_USER
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
    instantRemove: Boolean = false,
) {
    val context = LocalContext.current
    val manager = LocalDownloadUtil.current.downloadManager
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
        onStop = {
            // Local playlists stop with a nonzero stop reason; do the same
            // for individual tracks, preserving partial cache for resume.
            manager.setStopReason(mediaId, COLLECTION_STOP_REASON_USER)
            // A newly dispatched ADD may not yet be present in the manager.
            DownloadService.sendSetStopReason(
                context, ExoDownloadService::class.java,
                mediaId, COLLECTION_STOP_REASON_USER, false,
            )
        },
        onRemove = {
            DownloadService.sendRemoveDownload(
                context, ExoDownloadService::class.java, mediaId, false,
            )
        },
    )
}
