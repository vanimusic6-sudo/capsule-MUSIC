/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.utils

import android.content.Context
import android.content.res.Configuration
import timber.log.Timber
import java.util.Locale

fun reportException(throwable: Throwable) {
    /* Honors the runtime debug-logging switch instead of always writing stderr. */
    Timber.e(throwable)
}

@Suppress("DEPRECATION")
fun setAppLocale(context: Context, locale: Locale) {
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
}
