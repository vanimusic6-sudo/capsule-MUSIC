package com.nikhil.yt.playback.audio

import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * A policy change can cancel shared work while this Media3 loader still needs the song.
 * Rejoin a fresh job within the caller's existing timeout; never retry a cancelled loader
 * or an abandoned song. A short delay also bounds churn during repeated preference changes.
 */
internal suspend fun <T> awaitForegroundAudioResolve(
    isRelevant: suspend () -> Boolean,
    resolve: suspend () -> Result<T>,
): T {
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            return resolve().getOrThrow()
        } catch (timeout: TimeoutCancellationException) {
            throw timeout
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!isRelevant()) throw cancelled
            delay(50)
        }
    }
}

internal fun cancelledAudioLoad(cause: CancellationException): InterruptedIOException =
    InterruptedIOException("AUDIO load cancelled").apply { initCause(cause) }
