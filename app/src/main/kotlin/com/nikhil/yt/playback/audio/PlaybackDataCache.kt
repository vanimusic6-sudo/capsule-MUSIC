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
        var deliveredAudioBytes: Boolean = false,
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
        // A fast-swipe item is often resolved with PLAYBACK priority even though its CDN
        // stream is never opened. Prefetch-only TTL let such a signed URL sit untested for
        // minutes and later return a 403 on the first open. Apply the short lifetime to every
        // link until a real CDN read has confirmed that the link can deliver audio.
        val staleUnopenedLink =
            !entry.deliveredAudioBytes &&
                maxPrefetchedAgeMs != null &&
                now - entry.storedAtMs > maxPrefetchedAgeMs
        if (
            entry.context != currentContext() ||
            entry.expiresAtMs <= now + minimumRemainingMs ||
            staleUnopenedLink
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

    /** Only mark the *same* URL healthy after the transport returned actual audio bytes. */
    @Synchronized
    fun markDeliveredAudioBytes(mediaId: String, streamUrl: String) {
        entries[mediaId]
            ?.takeIf { it.data.streamUrl == streamUrl }
            ?.deliveredAudioBytes = true
    }

    @Synchronized
    fun remove(mediaId: String) { entries.remove(mediaId) }

    @Synchronized
    fun clear() { entries.clear() }
}
