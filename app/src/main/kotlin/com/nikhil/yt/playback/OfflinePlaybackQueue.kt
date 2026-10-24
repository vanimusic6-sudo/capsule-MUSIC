package com.nikhil.yt.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A projection of the saved queue, never a modification to a playlist or library. Main thread only. */
internal class OfflinePlaybackQueue(
    private val player: Player,
    private val scope: CoroutineScope,
    private val available: (MediaItem) -> Boolean,
) : Player.Listener {
    private var original: List<MediaItem>? = null
    private var projected = emptyList<MediaItem>()
    private var online = true
    private var applying = false
    private var revision = 0L
    private var checkJob: Job? = null
    private var selectedOriginalIndex = 0

    init { player.addListener(this) }
    fun savedItems(): List<MediaItem> = original ?: items()
    fun savedIndex(): Int = if (original == null) player.currentMediaItemIndex.coerceAtLeast(0)
        else originalIndex(player.currentMediaItemIndex).coerceAtLeast(0)
    private fun items() = (0 until player.mediaItemCount).map(player::getMediaItemAt)
    private fun originalIndex(index: Int): Int {
        val item = projected.getOrNull(index) ?: return selectedOriginalIndex
        val occurrence = projected.take(index + 1).count { it == item }
        return original.orEmpty().withIndex().filter { it.value == item }.getOrNull(occurrence - 1)?.index
            ?: selectedOriginalIndex
    }
    fun clear() {
        revision++; checkJob?.cancel(); original = null; projected = emptyList(); selectedOriginalIndex = 0
    }
    fun connectivityChanged(connected: Boolean) {
        if (connected == online) return
        online = connected
        if (connected) {
            revision++; checkJob?.cancel()
            original?.let { apply(it, selectedOriginalIndex) }
            original = null
        } else {
            original = items(); projected = items()
            selectedOriginalIndex = player.currentMediaItemIndex.coerceAtLeast(0)
            refresh()
        }
    }
    /** New queue selection must not prepare even a preload item before local eligibility is known. */
    suspend fun replaceOffline(items: List<MediaItem>, index: Int, position: Long, play: Boolean) {
        clear(); online = false; original = items; selectedOriginalIndex = index.coerceAtLeast(0)
        val expected = ++revision
        val eligible = withContext(Dispatchers.IO) { items.filter(available) }
        if (expected != revision || online) return
        applying = true
        try {
            projected = eligible
            val selected = items.getOrNull(index)
            val start = eligible.indexOf(selected).takeIf { it >= 0 }
                ?: items.drop(index.coerceAtLeast(0)).firstOrNull { it in eligible }?.let(eligible::indexOf) ?: 0
            player.setMediaItems(eligible, start, if (eligible.getOrNull(start) == selected) position else 0L)
            if (eligible.isNotEmpty()) { player.prepare(); player.playWhenReady = play }
            else player.playWhenReady = false
        } finally { applying = false }
    }
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (online || applying || reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        val current = items()
        if (current == projected) return
        // Reconcile explicit queue edits using occurrence matching, retaining hidden members.
        original = reconcileOfflineQueue(original.orEmpty(), projected, current)
        projected = current
        refresh()
    }
    fun refresh() {
        if (online || original == null) return
        checkJob?.cancel()
        selectedOriginalIndex = originalIndex(player.currentMediaItemIndex).coerceAtLeast(0)
        val expected = ++revision
        val snapshot = original.orEmpty()
        checkJob = scope.launch {
            val eligible = withContext(Dispatchers.IO) { snapshot.filter(available) }
            if (expected == revision && !online) apply(eligible, selectedOriginalIndex)
        }
    }
    private fun apply(desired: List<MediaItem>, fallbackIndex: Int) {
        val before = items()
        val currentIndex = player.currentMediaItemIndex
        val current = before.getOrNull(currentIndex)
        if (original != null && current != null) selectedOriginalIndex = originalIndex(currentIndex)
        applying = true
        try {
            if (current != null && current !in desired) {
                val next = original.orEmpty().drop(fallbackIndex.coerceAtLeast(0)).firstOrNull { it in desired }
                val index = next?.let(desired::indexOf) ?: 0
                val play = player.playWhenReady
                projected = desired
                player.setMediaItems(desired, index, 0L)
                if (desired.isNotEmpty()) { player.prepare(); player.playWhenReady = play }
                else player.playWhenReady = false
            } else {
                // Remove/insert around a healthy local item instead of resetting its decoder/position.
                var i = 0
                while (i < desired.size) {
                    if (i < player.mediaItemCount && player.getMediaItemAt(i) == desired[i]) { i++; continue }
                    val found = (i until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it) == desired[i] }
                    if (found != null) player.removeMediaItems(i, found) else player.addMediaItem(i, desired[i])
                    i++
                }
                if (player.mediaItemCount > desired.size) player.removeMediaItems(desired.size, player.mediaItemCount)
                projected = desired
            }
        } finally { applying = false }
    }
}

/** Visible removals/reordering are user edits; absent offline members retain their original slots. */
internal fun <T> reconcileOfflineQueue(original: List<T>, previous: List<T>, current: List<T>): List<T> {
    val unused = original.indices.toMutableList()
    val slots = previous.mapNotNull { item -> unused.firstOrNull { original[it] == item }?.also(unused::remove) }
    val remaining = current.toMutableList()
    val removed = slots.filter { index ->
        val match = remaining.indexOf(original[index])
        if (match >= 0) { remaining.removeAt(match); false } else true
    }.toSet()
    val result = original.filterIndexed { index, _ -> index !in removed }.toMutableList()
    // Reorder the surviving visible slots only; hidden entries keep their relative order.
    val survivors = previous.toMutableList()
    val visible = current.filter { item ->
        val match = survivors.indexOf(item)
        if (match >= 0) { survivors.removeAt(match); true } else false
    }
    var visibleIndex = 0
    val keptSlots = slots.filterNot { it in removed }.map { old -> old - removed.count { it < old } }.toSet()
    result.indices.filter { it in keptSlots }.forEach { result[it] = visible[visibleIndex++] }
    // Insert new visible entries next to their actual predecessor, not after every hidden item.
    var anchor = -1
    val existing = visible.toMutableList()
    current.forEach { item ->
        val known = existing.indexOf(item)
        if (known >= 0) {
            existing.removeAt(known)
            anchor = (anchor + 1 until result.size).firstOrNull { result[it] == item } ?: anchor
        } else { result.add(++anchor, item) }
    }
    return result
}
