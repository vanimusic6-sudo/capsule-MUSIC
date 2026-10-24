package com.nikhil.yt.playback

import androidx.media3.exoplayer.offline.Download

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
