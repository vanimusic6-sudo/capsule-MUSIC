package com.nikhil.yt.playback

import android.content.Context
import android.widget.Toast
import com.nikhil.yt.R
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.utils.completed
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private class Session(var ids: List<String>) {
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

    private const val MEMBERSHIP_PREFS = "capsule_online_playlist_download_members_v1"

    // Persist ownership in addition to Media3's per-track index. A process
    // restart must not make the Remove button forget songs from continuation
    // pages that the current menu has not loaded yet.
    private fun rememberedIds(context: Context, playlistId: String): List<String> =
        context.applicationContext.getSharedPreferences(MEMBERSHIP_PREFS, Context.MODE_PRIVATE)
            .getStringSet(playlistId, emptySet()).orEmpty().toList()

    fun memberIds(
        playlistId: String,
        context: Context,
        currentlyVisibleIds: Collection<String>,
    ): List<String> =
        (knownMembership[playlistId].orEmpty() + rememberedIds(context, playlistId) +
            currentlyVisibleIds).distinct()

    private fun appSaveMembership(context: Context, playlistId: String, ids: Collection<String>) {
        context.applicationContext.getSharedPreferences(MEMBERSHIP_PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(playlistId, ids.toSet()).apply()
    }

    fun start(
        playlistId: String,
        context: Context,
        downloadManager: DownloadManager,
        songs: List<SongItem>,
        knownDownloads: Map<String, Download> = emptyMap(),
    ) {
        // A new manual start replaces the old producer, never runs alongside it.
        sessions.remove(playlistId)?.job?.cancel()
        val firstPage = songs.distinctBy { it.id }
        lastServiceWakeId.remove(playlistId)
        val session = Session(firstPage.map { it.id })
        // Record current IDs immediately so Stop/Remove is valid even before
        // the network resolves further continuation pages.
        val previouslyKnown = rememberedIds(context, playlistId)
        knownMembership[playlistId] = (previouslyKnown + session.ids).distinct()
        appSaveMembership(context, playlistId, knownMembership[playlistId].orEmpty())
        sessions[playlistId] = session
        _enqueuing.value = _enqueuing.value + playlistId
        val appContext = context.applicationContext
        session.job = scope.launch {
            try {
                // A public playlist often arrives as one short initial page.
                // Resolve the FULL collection in this process-owned producer
                // BEFORE enqueuing. The More sheet can close or be cancelled
                // without leaving a producer that re-adds deleted tracks.
                val remote = withTimeoutOrNull(25_000L) {
                    withContext(Dispatchers.IO) {
                        // Some public radio/mix lists have no browsable
                        // playlist shelf but do expose a playable queue.
                        // One fallback request only, and only after explicit
                        // Download; opening More performs zero requests.
                        val fromBrowse = YouTube.playlist(playlistId.removePrefix("VL"))
                            .completed().getOrNull()?.songs.orEmpty()
                        fromBrowse.ifEmpty {
                            YouTube.queue(playlistId = playlistId.removePrefix("VL"))
                                .getOrNull().orEmpty()
                        }
                    }
                }.orEmpty()
                currentCoroutineContext().ensureActive()
                val uniqueSongs = remote.ifEmpty { firstPage }.distinctBy { it.id }
                if (uniqueSongs.isEmpty() && previouslyKnown.none(knownDownloads::containsKey)) {
                    // User/online playlist menus normally supply an empty first
                    // page. Give a real failure explanation instead of showing
                    // a dead, disabled button or silently doing nothing.
                    Toast.makeText(
                        appContext, R.string.capsule_online_playlist_download_unavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                session.ids = uniqueSongs.map { it.id }
                val allIds = (previouslyKnown + session.ids).distinct()
                knownMembership[playlistId] = allIds
                appSaveMembership(context, playlistId, allIds)
                var serviceStarted = false
                // One snapshot, not a linear scan of all downloaded tracks
                // for every playlist member (which would be quadratic).
                val indexed = knownDownloads +
                    downloadManager.currentDownloads.associateBy { it.request.id }
                for ((index, song) in uniqueSongs.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val existing = indexed[song.id]
                    if (existing != null && existing.state != Download.STATE_FAILED) {
                        if (existing.state == Download.STATE_STOPPED &&
                            existing.stopReason != DOWNLOAD_WAITING_FOR_SLOT) {
                            downloadManager.setStopReason(song.id, DOWNLOAD_WAITING_FOR_SLOT)
                        }
                        // Already queued, running or completed entries must not
                        // be restarted: preserve independent manual priority.
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
                            downloadManager.addDownload(request, DOWNLOAD_WAITING_FOR_SLOT)
                        }
                    }
                    if (index % 32 == 31) yield()
                }
                if (remote.isEmpty()) {
                    // Offline/public metadata failure: the persistent index still
                    // knows the requests for continuation-page tracks. Resume
                    // them without inventing unavailable SongItem metadata.
                    val shownIds = uniqueSongs.mapTo(hashSetOf()) { it.id }
                    previouslyKnown.asSequence().filterNot { it in shownIds }
                        .mapNotNull(indexed::get).forEach { saved ->
                            currentCoroutineContext().ensureActive()
                            when (saved.state) {
                                Download.STATE_STOPPED ->
                                    downloadManager.setStopReason(saved.request.id, DOWNLOAD_WAITING_FOR_SLOT)
                                Download.STATE_FAILED ->
                                    downloadManager.addDownload(saved.request, DOWNLOAD_WAITING_FOR_SLOT)
                                else -> Unit
                            }
                        }
                }
            } finally {
                if (sessions[playlistId] === session) {
                    sessions.remove(playlistId)
                    _enqueuing.value = _enqueuing.value - playlistId
                }
            }
        }
    }

    /** Match local playlists: tapping the active row pauses, never deletes cached bytes. */
    fun stop(
        playlistId: String,
        context: Context,
        downloadManager: DownloadManager,
        currentlyVisibleIds: Collection<String>,
    ) {
        val session = sessions.remove(playlistId)
        session?.job?.cancel()
        _enqueuing.value = _enqueuing.value - playlistId
        val ids = (knownMembership[playlistId].orEmpty() +
            session?.ids.orEmpty() + currentlyVisibleIds).distinct()
        stopCollectionDownloads(downloadManager, ids)
        // First ADD may still be waiting in the service intent queue; sending
        // STOP *after* that ADD prevents a delayed restart after user taps Stop.
        lastServiceWakeId[playlistId]?.let { first ->
            DownloadService.sendSetStopReason(
                context.applicationContext, ExoDownloadService::class.java,
                first, COLLECTION_STOP_REASON_USER, false,
            )
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
            rememberedIds(context, playlistId) + session?.ids.orEmpty() +
            currentlyVisibleIds).distinct()
        context.applicationContext.getSharedPreferences(MEMBERSHIP_PREFS, Context.MODE_PRIVATE)
            .edit().remove(playlistId).apply()
        removeCollectionDownloads(downloadManager, ids, preserveManual = false)
        lastServiceWakeId.remove(playlistId)?.let { first ->
            // Service ADD is queued before REMOVE, so a stale wake-up cannot
            // resurrect the downloaded item after the collection is deleted.
            DownloadService.sendRemoveDownload(
                context.applicationContext, ExoDownloadService::class.java, first, false,
            )
        }
    }
}
