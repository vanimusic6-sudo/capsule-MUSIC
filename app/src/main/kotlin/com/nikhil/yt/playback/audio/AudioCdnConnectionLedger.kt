package com.nikhil.yt.playback.audio

/**
 * How many googlevideo requests a connection has carried, and for how long.
 *
 * This exists to settle one question. Refusals arrive as a bare 403 on a link that is seconds old,
 * on the same host that serves the identical request when it is repeated twenty milliseconds
 * later. Nothing about the request changes between the two, so nothing about the request explains
 * it — which leaves the connection underneath. The captures could not say whether the refused
 * request was the first one on a freshly opened socket, because the only line carrying connection
 * identity was written for refusals and never for the requests that succeeded.
 *
 * OkHttp does not expose how many requests a [okhttp3.Connection] has served, so the count is kept
 * here, keyed by the connection's identity. That key is recycled once a connection is collected,
 * which for a diagnostic is acceptable: a recycled key can only understate a connection's age, and
 * the first request on a genuinely new connection is still reported as its first.
 *
 * Bounded, because an unbounded map fed by every connection of a long listening session is a leak.
 */
internal class AudioCdnConnectionLedger(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    /** One connection's first sighting and how many requests have gone over it since. */
    data class Use(val requestIndex: Int, val ageMs: Long)

    private class Entry(val firstSeenMs: Long, var requests: Int = 0)

    private val entries =
        object : LinkedHashMap<Int, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>): Boolean =
                size > capacity
        }

    /**
     * Records a request on [connectionId] and reports where in that connection's life it falls.
     *
     * A [connectionId] of -1 means OkHttp had no connection to name, which is not a connection
     * being used for the first time and must not be counted as one.
     */
    @Synchronized
    fun record(connectionId: Int, nowMs: Long): Use {
        if (connectionId == -1) return Use(requestIndex = 0, ageMs = 0L)
        val entry = entries.getOrPut(connectionId) { Entry(firstSeenMs = nowMs) }
        entry.requests += 1
        // A clock that has gone backwards is a clock, not an age.
        val age = (nowMs - entry.firstSeenMs).coerceAtLeast(0L)
        return Use(requestIndex = entry.requests, ageMs = age)
    }

    /** Forgotten wholesale when the route changes, like the rest of the CDN's memory of a network. */
    @Synchronized
    fun forget() {
        entries.clear()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 32
    }
}
