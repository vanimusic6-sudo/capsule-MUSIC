package com.nikhil.yt.playback.audio

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** A challenge stops the download queue, without blocking or rotating playback clients. */
internal class DownloadBotCheckGate(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val untilMs = AtomicLong(0L)

    fun remainingMs(): Long = (untilMs.get() - nowMs()).coerceAtLeast(0L)

    fun check() {
        if (remainingMs() > 0L) throw DownloadBotCheckException()
    }

    fun onChallenge() { untilMs.updateAndGet { maxOf(it, nowMs() + 60_000L) } }
}

internal class DownloadBotCheckException(cause: Throwable? = null) :
    IOException("YouTube bot detection: download queue is cooling down", cause)
