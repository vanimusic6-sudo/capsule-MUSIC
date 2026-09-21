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

/**
 * How long a just-resolved link is held before the first request for it leaves the phone.
 *
 * This was introduced as a refusal mitigation, on the observation that freshly-issued tokenized
 * GVS URLs sometimes reject the very first open and accept the unchanged retry moments later. It
 * does not do that, and the capture of 21 September says so twice over. The 30 opens that paid
 * the delay were the *worst* bucket in the session — 5 refused, 16.7% — against 0 of 47 for the
 * opens that skipped it. And link age cannot be the variable in the first place: the seven
 * refusals happened at ages of 8, 10, 12, 13 and 14 ms, and also at 1 306 ms and at 57 697 ms.
 * No wait separates that set from the ages that were served, which run over the same range.
 *
 * It is kept, for the other thing it does. The relevance check below it is the last word before
 * anything leaves the phone, and this window is the time that check has to notice a skip. The
 * same capture holds 12 opens that reached googlevideo and never delivered a byte because the
 * listener had already moved on; each one is a request that cannot produce audio and a socket
 * that has to be opened cold again afterwards. Widening or narrowing it is a question about
 * skipping, which is what it should be measured against — never about 403s.
 */
internal const val AUDIO_CDN_INITIAL_SETTLE_MS = 250L

/**
 * What is left of [AUDIO_CDN_INITIAL_SETTLE_MS] for a link resolved [resolvedAtElapsedMs] ago.
 *
 * Do not blindly sleep for every track: a link that has already sat for longer than the window
 * has nothing left to wait for, and one with no resolve time recorded is not this window's to
 * delay.
 */
internal fun audioCdnInitialSettleDelayMs(
    nowElapsedMs: Long,
    resolvedAtElapsedMs: Long,
): Long {
    if (resolvedAtElapsedMs <= 0L || nowElapsedMs < resolvedAtElapsedMs) return 0L
    val ageMs = nowElapsedMs - resolvedAtElapsedMs
    return (AUDIO_CDN_INITIAL_SETTLE_MS - ageMs).coerceAtLeast(0L)
}
