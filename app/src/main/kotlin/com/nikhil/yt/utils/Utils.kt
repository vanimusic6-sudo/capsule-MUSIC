/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.utils

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.Locale

/**
 * [runCatching], minus the part that breaks structured concurrency.
 *
 * `runCatching` catches `Throwable`, and a cancelled coroutine signals itself by throwing. So a
 * lyrics lookup for a track the listener has already skipped past was catching its own
 * cancellation, logging it, returning an empty list and carrying on working for a track nobody
 * wants — visible in a capture as "The coroutine scope left the composition" arriving as a
 * failure to be handled rather than as the scope leaving.
 *
 * The innertube module has had this for a while; it is `internal` there, so this is the same
 * discipline where the rest of the app can reach it rather than a second copy of the idea.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

fun reportException(throwable: Throwable) {
    // Coroutine cancellation is normal control flow (track switch, stale prefetch, shutdown),
    // not an application error. Do not pollute diagnostics with an E/ stack trace for it.
    if (throwable is CancellationException) return
    /* Honors the runtime debug-logging switch instead of always writing stderr. */
    Timber.e(throwable)
}

/**
 * Records a failure that the caller can safely recover from.
 *
 * Cancellation is control flow in coroutines and must never be converted into
 * a warning. Timber has no planted tree while diagnostics are disabled, so
 * this helper does not create a hidden Logcat or in-memory log stream.
 */
fun reportRecoverableException(
    tag: String,
    operation: String,
    throwable: Throwable,
) {
    if (throwable is CancellationException) throw throwable
    Timber.tag(tag).w(throwable, "%s failed", operation)
}

@Suppress("DEPRECATION")
fun setAppLocale(context: Context, locale: Locale) {
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
}
