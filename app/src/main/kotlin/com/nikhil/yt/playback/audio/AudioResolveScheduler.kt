package com.nikhil.yt.playback.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class AudioResolvePriority { PLAYBACK, PREFETCH, DOWNLOAD }

/** One extraction at a time. Foreground work can interrupt and requeue background work. */
internal class AudioResolveScheduler {
    private class Preempted : CancellationException("Foreground playback needs the resolver")
    private class Ticket(val mediaId: String, var priority: AudioResolvePriority) {
        val turn = CompletableDeferred<Unit>()
        var worker: Deferred<*>? = null
        var preempted = false
    }

    private val lock = Any()
    private val waiting = mutableListOf<Ticket>()
    private var active: Ticket? = null

    fun promote(mediaId: String) = synchronized(lock) {
        (waiting + listOfNotNull(active)).filter { it.mediaId == mediaId }.forEach {
            it.priority = AudioResolvePriority.PLAYBACK
        }
        preemptBackground()
    }

    suspend fun <T> run(mediaId: String, priority: AudioResolvePriority, block: suspend () -> T): T {
        while (true) {
            currentCoroutineContext().ensureActive()
            val ticket = Ticket(mediaId, priority)
            synchronized(lock) {
                waiting.add(ticket)
                dispatch()
                preemptBackground()
            }
            try {
                ticket.turn.await()
                return coroutineScope {
                    val work = async(start = CoroutineStart.LAZY) { block() }
                    synchronized(lock) {
                        ticket.worker = work
                        if (ticket.preempted) work.cancel(Preempted())
                    }
                    work.await()
                }
            } catch (_: Preempted) {
                // Only our own preemption is retried. Parent cancellation always propagates.
                currentCoroutineContext().ensureActive()
            } finally {
                synchronized(lock) {
                    waiting.remove(ticket)
                    if (active === ticket) active = null
                    dispatch()
                }
            }
        }
    }

    private fun dispatch() {
        if (active != null) return
        val next = waiting.minByOrNull { it.priority.ordinal } ?: return
        waiting.remove(next)
        active = next
        next.turn.complete(Unit)
    }

    private fun preemptBackground() {
        val running = active ?: return
        if (waiting.any { it.priority < running.priority && it.mediaId != running.mediaId }) {
            running.preempted = true
            running.worker?.cancel(Preempted())
        }
    }
}
