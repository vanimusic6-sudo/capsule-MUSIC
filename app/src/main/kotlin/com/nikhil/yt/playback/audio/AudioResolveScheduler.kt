package com.nikhil.yt.playback.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

enum class AudioResolvePriority(internal val schedulingRank: Int) {
    PLAYBACK(0),
    PREFETCH(100),
    DOWNLOAD(200),
    ;

    internal fun outranks(other: AudioResolvePriority): Boolean =
        schedulingRank < other.schedulingRank
}

/** One extraction at a time. Foreground work can interrupt and requeue background work. */
internal class AudioResolveScheduler(
    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val downloadStartSpacingMs: () -> Long = DownloadRequestPacing()::nextSpacingMs,
    private val promotedPrefetchRestartAfterMs: Long = PROMOTED_PREFETCH_RESTART_AFTER_MS,
) {
    private class Preempted : CancellationException("Foreground playback needs the resolver")
    private class Ticket(val mediaId: String, var priority: AudioResolvePriority) {
        val turn = CompletableDeferred<Unit>()
        var worker: Deferred<*>? = null
        var workerStartedAtMs: Long? = null
        var preempted = false
    }

    private val lock = Any()
    private val waiting = mutableListOf<Ticket>()
    private var active: Ticket? = null
    private var nextDownloadStartMs: Long? = null
    private val pendingPlaybackPromotions = mutableMapOf<String, Long>()

    fun promote(mediaId: String) = synchronized(lock) {
        val nowMs = monotonicNowMs()
        val matches =
            (waiting + listOfNotNull(active)).filter {
                it.mediaId == mediaId && it.priority == AudioResolvePriority.PREFETCH
            }

        if (matches.isEmpty()) {
            /*
             * ResolvingDataSource can demand the track a few milliseconds before
             * the shared prefetch creates its scheduler ticket. Remember that
             * foreground demand briefly so the next PREFETCH-labelled run starts
             * as PLAYBACK instead of losing its fallback chain.
             */
            pendingPlaybackPromotions[mediaId] = nowMs
        }

        matches.forEach { ticket ->
            ticket.priority = AudioResolvePriority.PLAYBACK
            val startedAtMs = ticket.workerStartedAtMs
            if (
                active === ticket &&
                startedAtMs != null &&
                promotedPrefetchRestartAfterMs >= 0L &&
                nowMs - startedAtMs >= promotedPrefetchRestartAfterMs
            ) {
                // A prefetch that has already spent several seconds inside the
                // extractor must not hold foreground playback hostage. Restart
                // only this stale request; young prefetches are still reused.
                ticket.preempted = true
                ticket.worker?.cancel(Preempted())
            }
        }
        preemptBackground()
    }

    /**
     * Returns the priority the resolver should use *now*.
     *
     * A young shared PREFETCH can be promoted while its extractor call is still
     * running. The fallback layer must observe that promotion too; otherwise the
     * scheduler says PLAYBACK while the client plan remains stuck at PREFETCH.
     */
    fun effectivePriority(
        mediaId: String,
        fallback: AudioResolvePriority,
    ): AudioResolvePriority =
        synchronized(lock) {
            val ticketPriority =
                (waiting + listOfNotNull(active))
                    .asSequence()
                    .filter { it.mediaId == mediaId }
                    .minByOrNull { it.priority.schedulingRank }
                    ?.priority
            if (ticketPriority != null) return@synchronized ticketPriority

            val promotedAt = pendingPlaybackPromotions[mediaId]
            if (
                fallback == AudioResolvePriority.PREFETCH &&
                promotedAt != null &&
                monotonicNowMs() - promotedAt in 0 until PENDING_PLAYBACK_PROMOTION_TTL_MS
            ) {
                AudioResolvePriority.PLAYBACK
            } else {
                fallback
            }
        }

    suspend fun <T> run(mediaId: String, priority: AudioResolvePriority, block: suspend () -> T): T {
        var effectivePriority =
            synchronized(lock) {
                val promotedAt = pendingPlaybackPromotions.remove(mediaId)
                if (
                    priority == AudioResolvePriority.PREFETCH &&
                    promotedAt != null &&
                    monotonicNowMs() - promotedAt in 0 until PENDING_PLAYBACK_PROMOTION_TTL_MS
                ) {
                    AudioResolvePriority.PLAYBACK
                } else {
                    priority
                }
            }

        while (true) {
            currentCoroutineContext().ensureActive()
            val ticket = Ticket(mediaId, effectivePriority)
            synchronized(lock) {
                waiting.add(ticket)
                dispatch()
                preemptBackground()
            }
            try {
                ticket.turn.await()
                return coroutineScope {
                    val work = async(start = CoroutineStart.LAZY) {
                        awaitDownloadStartWindow(ticket.priority)
                        synchronized(lock) {
                            ticket.workerStartedAtMs = monotonicNowMs()
                        }
                        block()
                    }
                    synchronized(lock) {
                        ticket.worker = work
                        if (ticket.preempted) work.cancel(Preempted())
                    }
                    work.await()
                }
            } catch (_: Preempted) {
                // Only our own preemption is retried. Parent cancellation always propagates.
                // A stale PREFETCH promoted by the loader retries as PLAYBACK.
                effectivePriority = ticket.priority
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

    private suspend fun awaitDownloadStartWindow(priority: AudioResolvePriority) {
        if (priority != AudioResolvePriority.DOWNLOAD) return

        val waitMs =
            synchronized(lock) {
                nextDownloadStartMs
                    ?.let { nextStart ->
                        (nextStart - monotonicNowMs()).coerceAtLeast(0L)
                    }
                    ?: 0L
            }

        if (waitMs > 0L) delay(waitMs)
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            // Choose once per actual start. Preemption during a wait retains this deadline.
            nextDownloadStartMs = monotonicNowMs() + downloadStartSpacingMs().coerceAtLeast(0L)
        }
    }

    private fun dispatch() {
        if (active != null) return
        val next = waiting.minByOrNull { it.priority.schedulingRank } ?: return
        waiting.remove(next)
        active = next
        next.turn.complete(Unit)
    }

    private fun preemptBackground() {
        val running = active ?: return
        if (waiting.any { it.priority.outranks(running.priority) }) {
            running.preempted = true
            running.worker?.cancel(Preempted())
        }
    }

    private companion object {
        const val PROMOTED_PREFETCH_RESTART_AFTER_MS = 4_000L
        const val PENDING_PLAYBACK_PROMOTION_TTL_MS = 10_000L
    }
}
