package com.nikhil.yt.playback.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException

/** One preparation per extraction session, owned independently of any waiting track. */
internal class SharedPrewarm(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 15_000L,
    private val prepare: suspend () -> Unit,
) {
    private var job: Deferred<Result<Unit>>? = null
    private var generation: Long = 0L

    @Synchronized
    fun start(): Deferred<Result<Unit>> {
        job?.let { return it }

        val attemptGeneration = ++generation
        val created =
            scope.async(start = CoroutineStart.LAZY) {
                val result =
                    try {
                        val completed = withTimeoutOrNull(timeoutMs) { prepare(); true } ?: false
                        if (completed) Result.success(Unit)
                        else Result.failure(SocketTimeoutException("Prewarm exceeded $timeoutMs ms"))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }

                if (result.isFailure) {
                    synchronized(this@SharedPrewarm) {
                        if (generation == attemptGeneration) {
                            job = null
                        }
                    }
                }
                result
            }

        job = created
        created.start()
        return created
    }

    suspend fun cancelAndJoin() {
        val pending = synchronized(this) { job }
        pending?.cancelAndJoin()
    }
}
