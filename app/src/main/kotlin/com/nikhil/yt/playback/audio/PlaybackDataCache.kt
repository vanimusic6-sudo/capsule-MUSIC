package com.nikhil.yt.playback.audio

/** Retain the complete extraction contract, including headers, loudness and tracking. */
internal class PlaybackDataCache(
    private val capacity: Int = 128,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val data: CapsuleAudioEngine.PlaybackData, val expiresAtMs: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(mediaId: String, minimumRemainingMs: Long = 5_000L): CapsuleAudioEngine.PlaybackData? {
        val entry = entries[mediaId] ?: return null
        if (entry.expiresAtMs <= nowMs() + minimumRemainingMs) {
            entries.remove(mediaId)
            return null
        }
        return entry.data
    }

    @Synchronized
    fun put(mediaId: String, data: CapsuleAudioEngine.PlaybackData) {
        entries[mediaId] = Entry(data, nowMs() + data.streamExpiresInSeconds.coerceAtLeast(1) * 1_000L)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun remove(mediaId: String) { entries.remove(mediaId) }

    @Synchronized
    fun clear() { entries.clear() }
}
