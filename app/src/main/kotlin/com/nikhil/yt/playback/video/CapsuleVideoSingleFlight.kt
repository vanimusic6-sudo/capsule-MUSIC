package com.nikhil.yt.playback.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Share live work only. A cancelled owner must never cancel the next VIDEO selection. */
internal class CapsuleVideoSingleFlight<T> {
    private data class Flight<T>(val owner: Job, val result: CompletableDeferred<T>)
    private val mutex = Mutex()
    private val flights = mutableMapOf<String, Flight<T>>()

    suspend fun run(key: String, block: suspend () -> T): T {
        val context = currentCoroutineContext()
        while (true) {
            context.ensureActive()
            var owns = false
            val flight = mutex.withLock {
                flights[key]?.takeIf { it.owner.isActive && !it.result.isCompleted }
                    ?: Flight<T>(context.job, CompletableDeferred()).also {
                        flights[key] = it
                        owns = true
                    }
            }
            if (!owns) {
                try {
                    return flight.result.await()
                } catch (cancelled: CancellationException) {
                    // The caller's own cancellation still propagates. An old
                    // owner's cancellation merely requires a fresh extraction.
                    context.ensureActive()
                    continue
                }
            }
            try {
                return block().also { flight.result.complete(it) }
            } catch (failure: Throwable) {
                flight.result.completeExceptionally(failure)
                throw failure
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock { flights.remove(key, flight) }
                }
            }
        }
    }
}
