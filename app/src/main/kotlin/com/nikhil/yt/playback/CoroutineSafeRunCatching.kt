package com.nikhil.yt.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Playback code frequently uses [runCatching] around suspend work. Kotlin's
 * standard helper catches [CancellationException] as an ordinary failure,
 * which is wrong for stale Media3 work: the cancellation can then be mapped to
 * a user-visible source error.
 *
 * Keep timeout cancellation as a Result failure because MusicService has an
 * explicit timeout-to-PlaybackException mapping. Every other coroutine
 * cancellation must escape unchanged.
 */
internal inline fun <R> runCatching(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (throwable: Throwable) {
        if (
            throwable is CancellationException &&
            throwable !is TimeoutCancellationException
        ) {
            throw throwable
        }
        Result.failure(throwable)
    }
