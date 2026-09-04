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

fun reportException(throwable: Throwable) {
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
