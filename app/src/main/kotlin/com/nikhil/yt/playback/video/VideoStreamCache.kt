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
        if (isFresh(entry.value)) return entry.value
        remove(key)
        return null
    }

    @Synchronized
    fun latest(videoId: String): T? = latestKeys[videoId]?.let { get(it) }

    @Synchronized
    fun put(videoId: String, key: String, value: T) {
        entries.filterValues { !isFresh(it.value) }.keys.toList().forEach(::remove)
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
