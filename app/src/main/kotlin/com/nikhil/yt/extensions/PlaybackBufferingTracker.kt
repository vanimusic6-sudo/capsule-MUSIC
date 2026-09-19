package com.nikhil.yt.extensions

/** Measures one selection/seek at a time, including transitions that remain BUFFERING. */
internal class PlaybackBufferingTracker(private val nowMs: () -> Long) {
    internal data class Event(
        val phase: String,
        val mediaId: String,
        val generation: Long,
        val kind: String,
        val durationMs: Long,
    )

    private data class Interval(val event: Event, val startedAtMs: Long)
    private var mediaId: String? = null
    private var index: Int = -1
    private var generation = 0L
    private var interval: Interval? = null
    private var wasReady = false
    private var startKind = "initial"

    fun reset() {
        mediaId = null
        index = -1
        interval = null
        wasReady = false
        startKind = "initial"
    }

    fun update(
        nextMediaId: String?,
        nextIndex: Int,
        buffering: Boolean,
        ready: Boolean,
        boundary: String? = null,
    ): List<Event> {
        val events = mutableListOf<Event>()
        val now = nowMs()
        fun finish(phase: String) {
            interval?.let {
                events += it.event.copy(phase = phase, durationMs = (now - it.startedAtMs).coerceAtLeast(0L))
            }
            interval = null
        }

        if (nextMediaId == null) {
            finish("cancel")
            reset()
            return events
        }
        if (nextMediaId != mediaId || nextIndex != index || boundary != null) {
            finish("cancel")
            startKind = if (mediaId == null) "initial" else boundary ?: "transition"
            if (nextMediaId != mediaId || nextIndex != index || boundary == "transition") wasReady = false
            mediaId = nextMediaId
            index = nextIndex
            generation += 1
        }
        if (buffering) {
            if (interval == null) {
                val kind = if (startKind == "rebuffer" && !wasReady) "initial" else startKind
                val event = Event("start", nextMediaId, generation, kind, 0L)
                interval = Interval(event, now)
                events += event
            }
        } else {
            finish(if (ready) "end" else "cancel")
            wasReady = ready
            startKind = if (ready) "rebuffer" else "initial"
        }
        return events
    }
}
