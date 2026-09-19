package com.nikhil.yt.playback.audio

internal enum class AudioCdnOpenSource {
    CACHED,
    JOINED_INFLIGHT,
    ON_DEMAND,
}

internal data class AudioCdnOpenContext(
    val mediaId: String,
    val resolvedAtElapsedMs: Long,
    val source: AudioCdnOpenSource,
    val streamClient: String?,
    /**
     * Slice size InnerTubeX sizes for this stream's client, or 0 when it named none.
     *
     * The library works this out per client and Metrolist has always used it; Capsule opted out of
     * the mechanism and asked for whole files instead. Carrying it here lets the request be sized
     * the way the library intends rather than by a constant chosen from failure counts.
     */
    val rangeChunkSizeBytes: Long = 0L,
)

internal const val AUDIO_CDN_INITIAL_SETTLE_MS = 250L

/**
 * Freshly-issued tokenized GVS URLs in field captures sometimes reject the very first open
 * and accept the unchanged retry shortly afterwards. Do not blindly sleep for every track:
 * only finish the small 250 ms age window when the URL was resolved immediately before use.
 */
internal fun audioCdnInitialSettleDelayMs(
    nowElapsedMs: Long,
    resolvedAtElapsedMs: Long,
): Long {
    if (resolvedAtElapsedMs <= 0L || nowElapsedMs < resolvedAtElapsedMs) return 0L
    val ageMs = nowElapsedMs - resolvedAtElapsedMs
    return (AUDIO_CDN_INITIAL_SETTLE_MS - ageMs).coerceAtLeast(0L)
}
