package com.nikhil.yt.playback.video

/** The LRU and the latest-quality index are changed atomically, including expiry and eviction. */
internal class VideoStreamCache<T>(
    private val maxEntries: Int,
    private val isFresh: (T) -> Boolean,
) {
    private data class Entry<T>(val videoId: String, val value: T)
    private val entries = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)
    private val latestKeys = HashMap<String, String>()

    init { require(maxEntries > 0) }

    @Synchronized
    operator fun get(key: String): T? {
        val entry = entries[key] ?: return null
        if (isFresh(entry.value)) {
            // The requested quality may already be in the LRU. A hit must
            // become the currently selected format, not leave a stale
            // latest() entry from a previous VIDEO quality selection.
            latestKeys[entry.videoId] = key
            return entry.value
        }
        remove(key)
        return null
    }

    @Synchronized
    fun latest(videoId: String): T? {
        latestKeys[videoId]?.let { key ->
            // An overwritten key must never return another track's stream.
            if (entries[key]?.videoId == videoId) {
                get(key)?.let { return it }
            } else {
                latestKeys.remove(videoId)
            }
        }
        // The most recently selected quality can expire while another signed
        // representation of this clip remains playable. Prefer that healthy
        // entry over another remote /player + extractor round trip.
        val surviving = entries.entries.toList().asReversed()
            .firstOrNull { it.value.videoId == videoId && isFresh(it.value.value) }
            ?.key
        return surviving?.let { get(it) }
    }

    @Synchronized
    fun put(videoId: String, key: String, value: T) {
        entries.filterValues { !isFresh(it.value) }.keys.toList().forEach(::remove)
        // Updating an existing key may replace a different video ID. Clear
        // its latest-quality pointer before installing the new entry.
        remove(key)
        entries[key] = Entry(videoId, value)
        latestKeys[videoId] = key
        while (entries.size > maxEntries) remove(entries.keys.first())
    }

    @Synchronized
    fun invalidate(videoId: String) {
        entries.filterValues { it.videoId == videoId }.keys.toList().forEach(::remove)
    }

    private fun remove(key: String) {
        val entry = entries.remove(key) ?: return
        latestKeys.remove(entry.videoId, key)
    }
}
