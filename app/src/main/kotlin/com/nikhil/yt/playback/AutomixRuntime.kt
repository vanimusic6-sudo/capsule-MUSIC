package com.nikhil.yt.playback

import androidx.media3.common.MediaItem
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns mutable Automix runtime state independently from [MusicService].
 *
 * Fetch policy and player queue mutations deliberately stay outside this
 * class. The runtime only owns state, cancellation resources and persisted
 * Automix identity so stale work can be reasoned about in one place.
 */
internal class AutomixRuntime {
    val items = MutableStateFlow<List<MediaItem>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    var job: Job? = null
    var seedMediaId: String? = null

    /**
     * Tracks items that Automix inserted into the active player queue.
     * This deliberately survives [clear]: the service uses it later to remove
     * only auto-added queue entries when infinite queue is disabled.
     */
    val autoAddedMediaIds: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    fun jobForSeed(mediaId: String): Job? =
        job?.takeIf { seedMediaId == mediaId }

    fun hasItemsOrActiveJobFor(mediaId: String): Boolean =
        seedMediaId == mediaId &&
            (items.value.isNotEmpty() || job?.isActive == true)

    /**
     * Restores process-persistent Automix state without depending on ExoPlayer
     * having finished its asynchronous queue restore first.
     */
    fun restore(
        restoredItems: List<MediaItem>,
        persistedSeedMediaId: String?,
        fallbackSeedMediaId: String?,
        restoredAutoAddedMediaIds: Collection<String>? = null,
    ) {
        job?.cancel()
        job = null
        items.value = restoredItems
        loading.value = false
        error.value = null
        seedMediaId = normalizeMediaId(persistedSeedMediaId) ?: normalizeMediaId(fallbackSeedMediaId)

        synchronized(autoAddedMediaIds) {
            autoAddedMediaIds.clear()
            restoredAutoAddedMediaIds
                .orEmpty()
                .mapNotNull(::normalizeMediaId)
                .forEach(autoAddedMediaIds::add)
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        items.value = emptyList()
        loading.value = false
        error.value = null
        seedMediaId = null
    }

    private fun normalizeMediaId(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}
