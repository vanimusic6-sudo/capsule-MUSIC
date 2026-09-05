package com.nikhil.yt.playback.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException

/** One preparation per extraction session, owned independently of any waiting track. */
internal class SharedPrewarm(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 8_000L,
    private val prepare: suspend () -> Unit,
) {
    private var job: Deferred<Result<Unit>>? = null

    @Synchronized
    fun start(): Deferred<Result<Unit>> =
        job ?: scope.async {
            try {
                val completed = withTimeoutOrNull(timeoutMs) { prepare(); true } ?: false
                if (completed) Result.success(Unit)
                else Result.failure(SocketTimeoutException("Prewarm exceeded $timeoutMs ms"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }.also { job = it }

    suspend fun cancelAndJoin() {
        val pending = synchronized(this) { job }
        pending?.cancelAndJoin()
    }
}
