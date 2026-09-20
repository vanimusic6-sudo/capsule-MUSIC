package com.nikhil.yt.playback.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * A running tally of how the CDN has treated this session.
 *
 * Every refusal in this investigation has been counted by hand, out of a log, one capture at a
 * time — and a rate read that way is worth very little. Three refusals in twelve opens and three
 * in a hundred look identical in a scrolling log and mean completely different things, and twice
 * a change that helped could not be credited because the before and after were eyeballed from
 * captures of different lengths.
 *
 * So the counts are kept as they happen and printed as one line. The fields are the ones five
 * captures have shown actually separate the cases: whether a refusal landed on a connection's
 * first request (they all have, so far), and how much of the traffic is redirects (each one ends
 * its socket, which manufactures more first requests).
 *
 * Counters only. No timers, no coroutines, nothing that runs when the app is idle — the tally
 * costs an atomic increment on requests that were going to hit the network anyway.
 */
internal object AudioCdnSessionStats {
    private val opens = AtomicLong()
    private val refusals = AtomicLong()
    private val refusalsOnFirstRequest = AtomicLong()
    private val requests = AtomicLong()
    private val redirects = AtomicLong()
    private val sockets = AtomicLong()

    fun recordOpen() {
        opens.incrementAndGet()
    }

    /**
     * One googlevideo response.
     *
     * [requestIndexOnConnection] is 1 for the first request down a socket; the refusal rate split
     * on that number is the single strongest signal these captures have produced.
     */
    fun recordResponse(statusCode: Int, requestIndexOnConnection: Int) {
        requests.incrementAndGet()
        if (requestIndexOnConnection == 1) sockets.incrementAndGet()
        when {
            statusCode in 300..399 -> redirects.incrementAndGet()
            isCdnRefusalStatus(statusCode) -> {
                refusals.incrementAndGet()
                if (requestIndexOnConnection == 1) refusalsOnFirstRequest.incrementAndGet()
            }
        }
    }

    /** Forgotten with the rest of the CDN's memory when the route changes. */
    fun forget() {
        opens.set(0)
        refusals.set(0)
        refusalsOnFirstRequest.set(0)
        requests.set(0)
        redirects.set(0)
        sockets.set(0)
    }

    /**
     * The tally as one line, or null when nothing has happened yet.
     *
     * Null rather than a row of zeroes, so a capture taken before any playback does not carry a
     * summary that looks like a clean session.
     */
    fun summary(): String? {
        val openCount = opens.get()
        val requestCount = requests.get()
        if (openCount == 0L && requestCount == 0L) return null
        val refusalCount = refusals.get()
        val socketCount = sockets.get()
        return buildString {
            append("cdn-session opens=").append(openCount)
            append(" refused=").append(refusalCount)
            append(" refusedPct=").append(percent(refusalCount, openCount))
            append(" refusedOnFirstRequest=").append(refusalsOnFirstRequest.get())
            append(" requests=").append(requestCount)
            append(" redirects=").append(redirects.get())
            append(" redirectPct=").append(percent(redirects.get(), requestCount))
            append(" sockets=").append(socketCount)
            append(" requestsPerSocket=").append(ratio(requestCount, socketCount))
        }
    }

    /** Whole percent of [part] in [whole]; "n/a" rather than a division by zero. */
    internal fun percent(part: Long, whole: Long): String =
        if (whole <= 0L) "n/a" else "${part * 100 / whole}%"

    /** [part] per [whole] to one decimal, without pulling in a locale-sensitive formatter. */
    internal fun ratio(part: Long, whole: Long): String =
        if (whole <= 0L) "n/a" else "${part * 10 / whole / 10}.${part * 10 / whole % 10}"
}
