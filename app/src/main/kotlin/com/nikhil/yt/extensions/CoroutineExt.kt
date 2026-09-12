/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.extensions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

fun <T> Flow<T>.collect(
    scope: CoroutineScope,
    action: suspend (value: T) -> Unit,
) {
    scope.launch {
        collect(action)
    }
}

fun <T> Flow<T>.collectLatest(
    scope: CoroutineScope,
    action: suspend (value: T) -> Unit,
) {
    scope.launch {
        collectLatest(action)
    }
}

/**
 * Best-effort background work may use this handler, but real failures must not
 * disappear silently. Cancellation is normal coroutine control flow and is not
 * reported as an application error.
 */
val SilentHandler =
    CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Timber.tag("Coroutine").w(
                throwable,
                "Best-effort coroutine failed",
            )
        }
    }
