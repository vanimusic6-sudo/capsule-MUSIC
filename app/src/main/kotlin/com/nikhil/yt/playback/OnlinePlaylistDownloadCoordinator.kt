package com.nikhil.yt.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.nikhil.yt.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Online playlists outlive the More-sheet composition.
 *
 * Previously the enqueue Job was remembered inside YouTubePlaylistMenu. Closing
 * and reopening the sheet forgot it, so Cancel removed existing Media3 entries
 * while the old producer kept re-adding the next 32 songs. Own this producer
 * at the application process level instead; Media3 itself owns the transfers.
 */
internal object OnlinePlaylistDownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class Session(val ids: List<String>) {
        var job: Job? = null
    }

    // All calls happen on the UI/main thread. Coroutine admission also runs
    // on Main.immediate, and the loop yields between bounded batches.
    private val sessions = mutableMapOf<String, Session>()
    private val knownMembership = mutableMapOf<String, List<String>>()
    // Keep this after the producing coroutine exits: Media3 can process the
    // first service ADD intent later than the menu's cancel confirmation.
    private val lastServiceWakeId = mutableMapOf<String, String>()
    private val _enqueuing = MutableStateFlow<Set<String>>(emptySet())
    val enqueuing = _enqueuing.asStateFlow()

    fun memberIds(playlistId: String, currentlyVisibleIds: Collection<String>): List<String> =
        (knownMembership[playlistId].orEmpty() + currentlyVisibleIds).distinct()

    fun start(
        playlistId: String,
        context: Context,
        downloadManager: DownloadManager,
        songs: List<SongItem>,
    ) {
        // A new manual start replaces the old producer, never runs alongside it.
        sessions.remove(playlistId)?.job?.cancel()
        val uniqueSongs = songs.distinctBy { it.id }
        val session = Session(uniqueSongs.map { it.id })
        knownMembership[playlistId] = session.ids
        sessions[playlistId] = session
        _enqueuing.value = _enqueuing.value + playlistId
        val appContext = context.applicationContext
        session.job = scope.launch {
            try {
                var serviceStarted = false
                // One snapshot, not a linear scan of all downloaded tracks
                // for every playlist member (which would be quadratic).
                val indexed = downloadManager.currentDownloads.associateBy { it.request.id }
                for ((index, song) in uniqueSongs.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val existing = indexed[song.id]
                    if (existing != null) {
                        if (existing.state != Download.STATE_COMPLETED) {
                            downloadManager.setStopReason(song.id, Download.STOP_REASON_NONE)
                        }
                    } else {
                        val request = DownloadRequest.Builder(song.id, song.id.toUri())
                            .setCustomCacheKey(song.id)
                            .setData(song.title.toByteArray())
                            .build()
                        if (!serviceStarted) {
                            // Keep the first service wake ID for a matching
                            // REMOVE intent even if that ADD is still queued.
                            lastServiceWakeId[playlistId] = song.id
                            DownloadService.sendAddDownload(
                                appContext, ExoDownloadService::class.java,
                                request, false,
                            )
                            serviceStarted = true
                        } else {
                            downloadManager.addDownload(request)
                        }
                    }
                    if (index % 32 == 31) yield()
                }
            } finally {
                if (sessions[playlistId] === session) {
                    sessions.remove(playlistId)
                    _enqueuing.value = _enqueuing.value - playlistId
                }
            }
        }
    }

    /** Cancel the producing coroutine *before* removing anything from Media3. */
    fun cancelAndRemove(
        playlistId: String,
        context: Context,
        downloadManager: DownloadManager,
        currentlyVisibleIds: Collection<String>,
    ) {
        val session = sessions.remove(playlistId)
        session?.job?.cancel()
        _enqueuing.value = _enqueuing.value - playlistId
        val ids = (knownMembership.remove(playlistId).orEmpty() +
            session?.ids.orEmpty() + currentlyVisibleIds).distinct()
        removeCollectionDownloads(downloadManager, ids)
        lastServiceWakeId.remove(playlistId)?.let { first ->
            // Service intents execute in order. A REMOVE after an in-flight
            // first ADD prevents late reappearance after the user cancelled.
            DownloadService.sendRemoveDownload(
                context.applicationContext, ExoDownloadService::class.java, first, false,
            )
        }
    }
}
