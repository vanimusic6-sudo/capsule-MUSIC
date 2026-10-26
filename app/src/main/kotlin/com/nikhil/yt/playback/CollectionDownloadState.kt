package com.nikhil.yt.playback

import android.content.Context
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService

/** A collection is complete only when every member is complete; empty is never downloaded. */
internal fun collectionDownloadState(songIds: Collection<String>, downloads: Map<String, Download>): Int {
    if (songIds.isEmpty()) return Download.STATE_STOPPED
    val states = songIds.distinct().map { downloads[it]?.visibleState }
    return when {
        states.all { it == Download.STATE_COMPLETED } -> Download.STATE_COMPLETED
        states.any { it == Download.STATE_QUEUED || it == Download.STATE_DOWNLOADING || it == Download.STATE_RESTARTING } ->
            Download.STATE_DOWNLOADING
        else -> Download.STATE_STOPPED
    }
}

/** Stop preserves partial bytes for resume. Media3 leaves completed entries complete, even in a completion race. */
internal fun stopCollectionDownloads(manager: androidx.media3.exoplayer.offline.DownloadManager, songIds: Collection<String>, preserveManual: Boolean = true) {
    val independent = if (preserveManual) manager.currentDownloads.filter { it.request.isManualDownload() }.map { it.request.id }.toSet() else emptySet()
    songIds.distinct().filterNot { it in independent }.forEach { manager.setStopReason(it, COLLECTION_STOP_REASON_USER) }
}

internal const val COLLECTION_STOP_REASON_USER = 1

/**
 * Media3 getCurrentDownloads() excludes terminal completed entries: those
 * persist only in DownloadIndex (and DownloadUtil.downloads). Filtering removal
 * to currentDownloads caused "Remove downloaded" to do nothing for completed
 * collections. Request deletion for every owned ID; Media3 safely ignores an
 * ID that was not present. Preserve manually prioritized active singles only
 * when a caller explicitly wants that policy.
 */
internal fun removeCollectionDownloads(
    manager: androidx.media3.exoplayer.offline.DownloadManager,
    songIds: Collection<String>,
    preserveManual: Boolean = false,
) {
    val protected = if (preserveManual) {
        manager.currentDownloads.asSequence()
            .filter { it.request.isManualDownload() }
            .map { it.request.id }.toSet()
    } else emptySet()
    songIds.distinct().filterNot { it in protected }.forEach(manager::removeDownload)
}

/**
 * The shared collection start/resume operation. A Media3 user stop reason is
 * sticky: adding the same DownloadRequest alone does not reliably resume it.
 * Use the same service path in local playlists, albums and multi-select.
 */
internal fun enqueueCollectionItem(
    context: Context,
    indexed: Map<String, Download>,
    request: DownloadRequest,
) {
    val saved = indexed[request.id]
    if (saved?.state == Download.STATE_COMPLETED) return
    if (saved?.state == Download.STATE_STOPPED) {
        DownloadService.sendSetStopReason(
            context, ExoDownloadService::class.java,
            request.id, Download.STOP_REASON_NONE, false,
        )
    } else {
        DownloadService.sendAddDownload(
            context, ExoDownloadService::class.java, request, false,
        )
        if (saved != null && saved.stopReason != Download.STOP_REASON_NONE) {
            DownloadService.sendSetStopReason(
                context, ExoDownloadService::class.java,
                request.id, Download.STOP_REASON_NONE, false,
            )
        }
    }
}

/**
 * Batch start/resume for local and remote collections. Start DownloadService
 * at most once; all remaining entries go directly to the already-initialized
 * Media3 manager. This avoids one foreground-service intent per song and
 * preserves the individual priority queue for manually selected tracks.
 */
internal fun enqueueCollectionDownloads(
    context: Context,
    manager: androidx.media3.exoplayer.offline.DownloadManager,
    indexed: Map<String, Download>,
    requests: List<DownloadRequest>,
) {
    var serviceStarted = false
    val active = manager.currentDownloads.associateBy { it.request.id }
    requests.distinctBy { it.id }.forEach { request ->
        val existing = indexed[request.id] ?: active[request.id]
        when (existing?.state) {
            Download.STATE_COMPLETED -> Unit
            Download.STATE_STOPPED -> {
                manager.setStopReason(request.id, Download.STOP_REASON_NONE)
            }
            Download.STATE_QUEUED, Download.STATE_DOWNLOADING,
            Download.STATE_RESTARTING -> Unit
            else -> {
                if (!serviceStarted) {
                    DownloadService.sendAddDownload(
                        context, ExoDownloadService::class.java, request, false,
                    )
                    serviceStarted = true
                } else {
                    manager.addDownload(request)
                }
            }
        }
    }
}
