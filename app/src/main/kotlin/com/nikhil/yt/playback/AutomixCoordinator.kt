package com.nikhil.yt.playback

import androidx.media3.common.MediaItem
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.playback.audio.CapsuleAudioEngine
import com.nikhil.yt.playback.queues.filterExplicit
import com.nikhil.yt.playback.queues.filterVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns Automix request policy and stale-work cancellation.
 *
 * MusicService still owns ExoPlayer queue mutations. This coordinator owns the
 * request chain, candidate construction, runtime state and request Jobs so the
 * service no longer needs to know how Automix talks to YouTube.
 */
internal class AutomixCoordinator(
    private val runtime: AutomixRuntime,
    private val scopeProvider: () -> CoroutineScope,
    private val stabilityGate: PlaybackStabilityGate,
    private val cacheRelatedSongs: suspend (String, List<SongItem>) -> Unit,
    private val playbackBlockedExceptionOrNull: () -> Throwable? = {
        CapsuleAudioEngine.playbackBlockedExceptionOrNull()
    },
) {
    val items = runtime.items
    val loading = runtime.loading
    val error = runtime.error
    val autoAddedMediaIds = runtime.autoAddedMediaIds

    private var playlistJob: Job? = null

    fun clear() {
        playlistJob?.cancel()
        playlistJob = null
        runtime.clear()
    }

    fun cancelTransientWork(clearError: Boolean = true) {
        runtime.job?.cancel()
        runtime.job = null
        playlistJob?.cancel()
        playlistJob = null
        runtime.loading.value = false
        if (clearError) runtime.error.value = null
    }

    fun removeAt(position: Int): MediaItem? {
        val current = runtime.items.value
        if (position !in current.indices) return null
        val removed = current[position]
        runtime.items.value = current.toMutableList().apply { removeAt(position) }
        return removed
    }

    fun ownedIdsSnapshot(): Set<String> =
        synchronized(autoAddedMediaIds) { autoAddedMediaIds.toSet() }

    fun clearOwnedIds() {
        autoAddedMediaIds.clear()
    }

    fun markAutoAdded(items: Collection<MediaItem>) {
        items.forEach { item ->
            item.mediaId.trim().takeIf { it.isNotEmpty() }?.let(autoAddedMediaIds::add)
        }
    }

    fun loadAlbum(
        albumId: String,
        expectedSeedMediaId: String?,
        currentSeedProvider: () -> String?,
    ) {
        playlistJob?.cancel()
        playlistJob =
            scopeProvider().launch {
                val playlistId =
                    withContext(Dispatchers.IO) {
                        YouTube.album(albumId).getOrNull()?.album?.playlistId
                    } ?: return@launch
                loadPlaylistInternal(playlistId, expectedSeedMediaId, currentSeedProvider)
            }
    }

    fun loadPlaylist(
        playlistId: String,
        expectedSeedMediaId: String?,
        currentSeedProvider: () -> String?,
    ) {
        playlistJob?.cancel()
        playlistJob =
            scopeProvider().launch {
                loadPlaylistInternal(playlistId, expectedSeedMediaId, currentSeedProvider)
            }
    }

    private suspend fun loadPlaylistInternal(
        playlistId: String,
        expectedSeedMediaId: String?,
        currentSeedProvider: () -> String?,
    ) {
        val first = withContext(Dispatchers.IO) {
            YouTube.next(WatchEndpoint(playlistId = playlistId)).getOrNull()
        } ?: return
        val continuationPlaylistId = first.endpoint.playlistId?.takeIf { it.isNotBlank() } ?: return
        val second = withContext(Dispatchers.IO) {
            YouTube.next(WatchEndpoint(playlistId = continuationPlaylistId)).getOrNull()
        } ?: return

        val currentSeed = currentSeedProvider()?.trim()?.takeIf { it.isNotBlank() }
        if (expectedSeedMediaId != null && currentSeed != expectedSeedMediaId) return

        runtime.items.value = second.items.map { it.toMediaItem() }
        runtime.seedMediaId = currentSeed
    }

    fun refresh(
        seedMediaId: String,
        hideExplicit: Boolean,
        hideVideo: Boolean,
        queueIdsProvider: () -> Set<String>,
        isRelevant: suspend (String) -> Boolean,
        noSimilarSongsMessage: () -> String,
        failureMessage: () -> String,
    ) {
        val seed = seedMediaId.trim().takeIf { it.isNotBlank() } ?: return
        if (runtime.hasItemsOrActiveJobFor(seed)) return

        runtime.job?.cancel()
        runtime.job = null
        runtime.items.value = emptyList()
        runtime.loading.value = true
        runtime.error.value = null
        runtime.seedMediaId = seed

        runtime.job =
            scopeProvider().launch {
                try {
                    stabilityGate.awaitStable {
                        runtime.seedMediaId == seed && isRelevant(seed)
                    }
                    playbackBlockedExceptionOrNull()?.let { throw it }

                    val result =
                        withContext(Dispatchers.IO) {
                            YouTube.next(WatchEndpoint(videoId = seed)).getOrThrow()
                        }

                    if (!ownsRelevantSeed(seed, isRelevant)) return@launch finishIdleIfOwned(seed)

                    val queueIds = queueIdsProvider()
                    val fromNext =
                        filterCandidates(
                            result.items.map { it.toMediaItem() },
                            queueIds,
                            hideExplicit,
                            hideVideo,
                        )

                    val relatedSongs =
                        result.relatedEndpoint
                            ?.let { endpoint ->
                                withContext(Dispatchers.IO) {
                                    YouTube.related(endpoint).getOrNull()?.songs.orEmpty()
                                }
                            }
                            .orEmpty()

                    withContext(Dispatchers.IO) { cacheRelatedSongs(seed, relatedSongs) }

                    val related =
                        filterCandidates(
                            relatedSongs.map { it.toMediaItem() },
                            queueIds,
                            hideExplicit,
                            hideVideo,
                        )

                    val poolBase =
                        mergeAutomixCandidates(
                            sources = listOf(fromNext, related),
                            excludedIds = emptySet(),
                            limit = 50,
                        )

                    val extra =
                        if (poolBase.size >= 25 || result.endpoint.playlistId.isNullOrBlank()) {
                            emptyList()
                        } else {
                            withContext(Dispatchers.IO) {
                                YouTube.next(WatchEndpoint(playlistId = result.endpoint.playlistId))
                                    .getOrNull()
                                    ?.items
                                    .orEmpty()
                                    .map { it.toMediaItem() }
                            }.let { items ->
                                filterCandidates(items, queueIds, hideExplicit, hideVideo)
                            }
                        }

                    val pool =
                        mergeAutomixCandidates(
                            sources = listOf(poolBase, extra),
                            excludedIds = emptySet(),
                            limit = 75,
                        )

                    if (!ownsRelevantSeed(seed, isRelevant)) return@launch finishIdleIfOwned(seed)

                    runtime.items.value = pool
                    runtime.error.value = if (pool.isEmpty()) noSimilarSongsMessage() else null
                    runtime.loading.value = false
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (runtime.seedMediaId == seed) {
                        runtime.loading.value = false
                        runtime.error.value = failure.localizedMessage ?: failureMessage()
                    }
                }
            }
    }

    fun expandNow(
        seedMediaId: String,
        hideExplicit: Boolean,
        hideVideo: Boolean,
        queueIdsProvider: () -> Set<String>,
        isRelevant: suspend (String) -> Boolean,
        onAddItems: (List<MediaItem>) -> Unit,
        noSimilarSongsMessage: () -> String,
        failureMessage: () -> String,
    ) {
        val seed = seedMediaId.trim().takeIf { it.isNotBlank() } ?: return
        runtime.job?.cancel()
        runtime.job = null
        runtime.items.value = emptyList()
        runtime.loading.value = true
        runtime.error.value = null
        runtime.seedMediaId = seed

        runtime.job =
            scopeProvider().launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            YouTube.next(WatchEndpoint(videoId = seed)).getOrThrow()
                        }
                    if (!ownsRelevantSeed(seed, isRelevant)) return@launch finishIdleIfOwned(seed)

                    val initialQueueIds = queueIdsProvider()
                    val fromNext =
                        filterCandidates(
                            result.items.map { it.toMediaItem() },
                            initialQueueIds,
                            hideExplicit,
                            hideVideo,
                        )

                    val addedNow = ArrayList<MediaItem>(32)
                    if (fromNext.isNotEmpty()) {
                        val toAdd = fromNext.take(25)
                        onAddItems(toAdd)
                        markAutoAdded(toAdd)
                        addedNow.addAll(toAdd)
                    }

                    val queueIdsAfterNext = queueIdsProvider()
                    val relatedSongs =
                        result.relatedEndpoint
                            ?.let { endpoint ->
                                withContext(Dispatchers.IO) {
                                    YouTube.related(endpoint).getOrNull()?.songs.orEmpty()
                                }
                            }
                            .orEmpty()
                    val related =
                        filterCandidates(
                            relatedSongs.map { it.toMediaItem() },
                            queueIdsAfterNext,
                            hideExplicit,
                            hideVideo,
                        )

                    if (addedNow.isEmpty() && related.isNotEmpty()) {
                        val toAdd = related.take(25)
                        onAddItems(toAdd)
                        markAutoAdded(toAdd)
                        addedNow.addAll(toAdd)
                    }

                    val queueIdsAfterAdds = queueIdsProvider()
                    val extra =
                        result.endpoint.playlistId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { playlistId ->
                                withContext(Dispatchers.IO) {
                                    YouTube.next(WatchEndpoint(playlistId = playlistId))
                                        .getOrNull()
                                        ?.items
                                        .orEmpty()
                                        .map { it.toMediaItem() }
                                }
                            }
                            .orEmpty()
                            .let { items ->
                                filterCandidates(items, queueIdsAfterAdds, hideExplicit, hideVideo)
                            }

                    if (!ownsRelevantSeed(seed, isRelevant)) return@launch finishIdleIfOwned(seed)

                    val addedIds = addedNow.mapTo(mutableSetOf()) { it.mediaId }
                    val pool =
                        mergeAutomixCandidates(
                            sources = listOf(fromNext, related, extra),
                            excludedIds = addedIds,
                            limit = 75,
                        )
                    runtime.items.value = pool
                    runtime.error.value =
                        if (addedNow.isEmpty() && pool.isEmpty()) noSimilarSongsMessage() else null
                    runtime.loading.value = false
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (runtime.seedMediaId == seed) {
                        runtime.loading.value = false
                        runtime.error.value = failure.localizedMessage ?: failureMessage()
                    }
                }
            }
    }

    fun recoverAfterQueueEnded(
        seedMediaId: String,
        hideExplicit: Boolean,
        hideVideo: Boolean,
        isBeforeApplyRelevant: suspend () -> Boolean,
        isAfterApplyRelevant: suspend () -> Boolean,
        onReplaceQueue: (List<MediaItem>) -> Unit,
        noSimilarSongsMessage: () -> String,
        failureMessage: () -> String,
    ) {
        val seed = seedMediaId.trim().takeIf { it.isNotBlank() } ?: return
        runtime.job?.cancel()
        runtime.job = null
        runtime.loading.value = true
        runtime.error.value = null
        runtime.seedMediaId = seed

        runtime.job =
            scopeProvider().launch {
                try {
                    val nextResult =
                        withContext(Dispatchers.IO) {
                            YouTube.next(WatchEndpoint(videoId = seed)).getOrThrow()
                        }
                    if (!isBeforeApplyRelevant()) return@launch finishIdleIfOwned(seed)

                    val radioItems =
                        filterCandidates(
                            nextResult.items.map { it.toMediaItem() },
                            excludedIds = setOf(seed),
                            hideExplicit = hideExplicit,
                            hideVideo = hideVideo,
                        )
                    if (radioItems.isEmpty()) {
                        runtime.items.value = emptyList()
                        runtime.error.value = noSimilarSongsMessage()
                        runtime.loading.value = false
                        return@launch
                    }

                    // Claim the item that is about to become current before mutating
                    // ExoPlayer. The transition callback can now recognize this Job
                    // as relevant instead of launching a duplicate Automix request.
                    runtime.seedMediaId = radioItems.first().mediaId
                    clearOwnedIds()
                    markAutoAdded(radioItems)
                    onReplaceQueue(radioItems)

                    val continuationItems =
                        nextResult.endpoint.playlistId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { playlistId ->
                                withContext(Dispatchers.IO) {
                                    YouTube.next(WatchEndpoint(playlistId = playlistId))
                                        .getOrNull()
                                        ?.items
                                        .orEmpty()
                                        .map { it.toMediaItem() }
                                }
                            }
                            .orEmpty()

                    if (!isAfterApplyRelevant()) {
                        runtime.loading.value = false
                        return@launch
                    }

                    val currentSeed = runtime.seedMediaId
                    val pool =
                        filterCandidates(
                            continuationItems,
                            excludedIds = setOf(seed),
                            hideExplicit = hideExplicit,
                            hideVideo = hideVideo,
                        )
                    if (runtime.seedMediaId == currentSeed) {
                        runtime.items.value = pool
                        runtime.loading.value = false
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    runtime.loading.value = false
                    runtime.error.value = failure.localizedMessage ?: failureMessage()
                }
            }
    }

    private suspend fun ownsRelevantSeed(
        seed: String,
        isRelevant: suspend (String) -> Boolean,
    ): Boolean = runtime.seedMediaId == seed && isRelevant(seed)

    private fun finishIdleIfOwned(seed: String) {
        if (runtime.seedMediaId == seed) runtime.loading.value = false
    }

    private fun filterCandidates(
        items: List<MediaItem>,
        excludedIds: Set<String>,
        hideExplicit: Boolean,
        hideVideo: Boolean,
    ): List<MediaItem> =
        items
            .filter { it.mediaId !in excludedIds }
            .filterExplicit(hideExplicit)
            .filterVideo(hideVideo)
}

internal fun mergeAutomixCandidates(
    sources: List<List<MediaItem>>,
    excludedIds: Set<String>,
    limit: Int,
): List<MediaItem> {
    if (limit <= 0) return emptyList()
    return sources
        .asSequence()
        .flatten()
        .filter { it.mediaId.isNotBlank() && it.mediaId !in excludedIds }
        .distinctBy { it.mediaId }
        .take(limit)
        .toList()
}
