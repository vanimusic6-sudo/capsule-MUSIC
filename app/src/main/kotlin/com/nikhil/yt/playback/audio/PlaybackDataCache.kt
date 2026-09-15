package com.nikhil.yt.playback.audio

/** Retain the complete extraction contract, including headers, loudness and tracking. */
internal class PlaybackDataCache(
    private val capacity: Int = 128,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val currentContext: () -> Any? = { null },
) {
    private data class Entry(
        val data: CapsuleAudioEngine.PlaybackData,
        val expiresAtMs: Long,
        val context: Any?,
        val storedAtMs: Long,
        val prefetched: Boolean,
    )
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(mediaId: String, minimumRemainingMs: Long = 5_000L): CapsuleAudioEngine.PlaybackData? =
        getValidEntry(mediaId, minimumRemainingMs, maxPrefetchedAgeMs = null)

    @Synchronized
    fun getForPlayback(
        mediaId: String,
        maxPrefetchedAgeMs: Long,
        minimumRemainingMs: Long = 5_000L,
    ): CapsuleAudioEngine.PlaybackData? =
        getValidEntry(
            mediaId = mediaId,
            minimumRemainingMs = minimumRemainingMs,
            maxPrefetchedAgeMs = maxPrefetchedAgeMs,
        )

    private fun getValidEntry(
        mediaId: String,
        minimumRemainingMs: Long,
        maxPrefetchedAgeMs: Long?,
    ): CapsuleAudioEngine.PlaybackData? {
        val entry = entries[mediaId] ?: return null
        val now = nowMs()
        val stalePrefetch =
            entry.prefetched &&
                maxPrefetchedAgeMs != null &&
                now - entry.storedAtMs > maxPrefetchedAgeMs
        if (
            entry.context != currentContext() ||
            entry.expiresAtMs <= now + minimumRemainingMs ||
            stalePrefetch
        ) {
            entries.remove(mediaId)
            return null
        }
        return entry.data
    }

    @Synchronized
    fun put(
        mediaId: String,
        data: CapsuleAudioEngine.PlaybackData,
        context: Any? = currentContext(),
        prefetched: Boolean = false,
    ) {
        val now = nowMs()
        entries[mediaId] =
            Entry(
                data = data,
                expiresAtMs = now + data.streamExpiresInSeconds.coerceAtLeast(1) * 1_000L,
                context = context,
                storedAtMs = now,
                prefetched = prefetched,
            )
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun remove(mediaId: String) { entries.remove(mediaId) }

    @Synchronized
    fun clear() { entries.clear() }
}
