package com.nikhil.yt.playback.audio

/**
 * A monotonic, process-wide quiet period for OPTIONAL YouTube requests after a
 * confirmed challenge. Foreground playback and offline files remain available.
 * This honours a server signal; it never impersonates a client or changes credentials.
 */
internal class OptionalYouTubeTrafficQuiet(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val botQuietMs: Long = 120_000L,
    private val rateQuietMs: Long = 600_000L,
) {
    private var quietUntilMs = 0L

    @Synchronized fun remainingMs(): Long = (quietUntilMs - nowMs()).coerceAtLeast(0L)
    @Synchronized fun observeBotChallenge() {
        quietUntilMs = maxOf(quietUntilMs, nowMs() + botQuietMs)
    }
    @Synchronized fun observeRateLimit() {
        quietUntilMs = maxOf(quietUntilMs, nowMs() + rateQuietMs)
    }
    @Synchronized fun clear() { quietUntilMs = 0L }
}
