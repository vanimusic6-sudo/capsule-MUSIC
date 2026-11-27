package com.nikhil.yt.playback.audio

/** A hint, not a verdict about access rights or a global CDN ban. */
internal data class AudioRouteAdvice(
    val preferAac: Boolean = false,
    val acceptedClient: String? = null,
) {
    fun prioritize(configured: List<String>): List<String> {
        val preferred = acceptedClient?.takeIf { it in configured } ?: return configured
        return listOf(preferred) + configured.filterNot { it == preferred }
    }
}

internal class AudioRouteMemory(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ttlMs: Long = 30 * 60_000L,
    private val capacity: Int = 256,
) {
    private data class Route(val client: String, val itag: Int, val host: String)
    private data class Entry(
        val refusals: LinkedHashMap<String, Route> = LinkedHashMap(),
        var accepted: Route? = null,
        var until: Long = 0L,
        var pipeRetryAfter: Long = 0L,
    )
    private var session: Any? = null
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)

    init { require(ttlMs > 0 && capacity > 0) }

    private fun route(client: String?, itag: Int?, host: String?): Route? {
        if (client.isNullOrBlank() || itag == null || itag !in 1..9999 ||
            host == null || !host.endsWith(".googlevideo.com", ignoreCase = true)) return null
        return Route(client.substringBefore('@').substringBefore("__").trim(), itag, host.lowercase())
    }

    private fun live(id: String): Entry? {
        val e = entries[id] ?: return null
        if (e.until <= nowMs()) { entries.remove(id); return null }
        return e
    }

    /** Count distinct signed generations only. Reopening one failed URL is not new evidence. */
    @Synchronized fun refused(identity: Any, id: String, client: String?, itag: Int?, host: String?, ref: String) {
        val failed = route(client, itag, host) ?: return
        if (session != identity) { entries.clear(); session = identity }
        val e = live(id) ?: Entry().also { entries[id] = it }
        if (ref in e.refusals) return
        e.refusals[ref] = failed
        while (e.refusals.size > 12) e.refusals.remove(e.refusals.keys.first())
        if (e.accepted?.let { it.client == failed.client && it.itag == failed.itag } == true) e.accepted = null
        e.until = nowMs() + ttlMs
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    /** A real positive read, not a metadata success or a cache-only READY. */
    @Synchronized fun accepted(identity: Any, id: String, client: String?, itag: Int?, host: String?) {
        if (session != identity) return
        val e = live(id) ?: return
        val accepted = route(client, itag, host) ?: return
        // Keep only one confirmed route and avoid extending TTL on every range.
        if (e.accepted == accepted) return
        e.accepted = accepted
        if (accepted.client == PIPE_AUDIO_CLIENT) e.pipeRetryAfter = 0L
        e.until = nowMs() + ttlMs
    }

    @Synchronized fun advice(identity: Any, id: String): AudioRouteAdvice {
        if (session != identity) return AudioRouteAdvice()
        val e = live(id) ?: return AudioRouteAdvice()
        val opusRefusals = e.refusals.values.count { it.itag in 249..251 }
        val accepted = e.accepted
        val preferAac = opusRefusals >= 2 && (accepted == null || accepted.itag !in 249..251)
        return AudioRouteAdvice(preferAac, accepted?.client)
    }

    /** At most one independent parser probe per minute for a repeatedly refused track.
     * A confirmed parser link which later received 403 is not retried during this evidence TTL.
     */
    @Synchronized fun claimPipePipe(identity: Any, id: String): Boolean {
        if (session != identity) return false
        val e = live(id) ?: return false
        if (e.refusals.size < 2 || e.pipeRetryAfter > nowMs() ||
            e.refusals.values.any { it.client == PIPE_AUDIO_CLIENT }) return false
        e.pipeRetryAfter = nowMs() + 60_000L
        return true
    }

    @Synchronized fun clear() { entries.clear(); session = null }
}

internal const val PIPE_AUDIO_CLIENT = "PIPEPIPE_AUDIO"

internal object AudioRouteHistory {
    val memory = AudioRouteMemory()
}
