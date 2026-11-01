package com.nikhil.yt.playback.audio

/** Per-track, bounded failure history. Playback and downloads own separate instances.
 * Reading a live entry keeps it in the LRU but never extends its verdict lifetime.
 */
internal class TrackClientFailureCache(
    private val capacity: Int = 256,
    private val ttlMs: Long = 5 * 60 * 1000L,
) {
    private data class Entry(val clients: Set<String>, val failedAtMs: Long)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)

    init { require(capacity > 0 && ttlMs > 0) }

    @Synchronized
    fun record(mediaId: String, client: String, nowMs: Long) {
        prune(nowMs)
        entries[mediaId] = Entry(entries[mediaId]?.clients.orEmpty() + client, nowMs)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun clients(mediaId: String, nowMs: Long): Set<String> {
        prune(nowMs)
        return entries[mediaId]?.clients?.toSet().orEmpty()
    }

    @Synchronized fun remove(mediaId: String) { entries.remove(mediaId) }
    @Synchronized fun clear() { entries.clear() }

    private fun prune(nowMs: Long) {
        entries.entries.removeAll { (_, entry) -> (nowMs - entry.failedAtMs) !in 0 until ttlMs }
    }
}
