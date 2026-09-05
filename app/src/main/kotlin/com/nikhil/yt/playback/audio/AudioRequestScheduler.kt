package com.nikhil.yt.playback.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class AudioRequestPriority { PLAYBACK, PREFETCH, DOWNLOAD, MAINTENANCE }

/** One active extraction; foreground requests precede queued background work. */
internal class AudioRequestScheduler {
    private class Request(val mediaId: String?, val priority: AudioRequestPriority) {
        val ready = CompletableDeferred<Unit>()
    }
    private val lock = Any()
    private val pending = mutableListOf<Request>()
    private var active: Request? = null
    private var foregroundMediaId: String? = null

    fun select(mediaId: String?) = synchronized(lock) {
        foregroundMediaId = mediaId
    }

    suspend fun <T> run(
        mediaId: String? = null,
        priority: AudioRequestPriority = AudioRequestPriority.PLAYBACK,
        block: suspend () -> T,
    ): T {
        val request = Request(mediaId, priority)
        synchronized(lock) {
            pending += request
            dispatch()
        }
        try {
            request.ready.await()
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            synchronized(lock) {
                if (active === request) active = null else pending.remove(request)
                dispatch()
            }
        }
    }

    private fun dispatch() {
        if (active != null) return
        val next = pending.minByOrNull {
            if (it.mediaId != null && it.mediaId == foregroundMediaId) 0 else it.priority.ordinal
        } ?: return
        pending.remove(next)
        active = next
        next.ready.complete(Unit)
    }
}
