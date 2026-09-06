package com.nikhil.yt.playback

import androidx.media3.common.MediaItem
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns mutable Automix runtime state independently from [MusicService].
 *
 * This intentionally does not decide what to fetch or when to extend the
 * player queue. Those policies stay unchanged in MusicService for this
 * extraction step; only their mutable runtime resources move behind one
 * ownership boundary.
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

    fun clear() {
        job?.cancel()
        job = null
        items.value = emptyList()
        loading.value = false
        error.value = null
        seedMediaId = null
    }
}
