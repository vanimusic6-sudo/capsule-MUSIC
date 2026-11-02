package com.nikhil.yt.ui.component

import com.nikhil.yt.utils.GlobalLog
import timber.log.Timber

/** Opt-in counts, not a second clock, timer, player listener or frame producer. */
internal class LyricsClockDiagnostics {
    private var since = 0L
    private var sourceAt = 0L
    private var source = Long.MIN_VALUE
    private var visual = Long.MIN_VALUE
    private var frames = 0
    private var sourceUpdates = 0
    private var visualUpdates = 0
    private var maxSourceHold = 0L

    fun sample(sourceMs: Long, visualMs: Long, nowNanos: Long) {
        if (!GlobalLog.isEnabled) { since = 0L; source = Long.MIN_VALUE; return }
        if (since == 0L) { since = nowNanos; sourceAt = nowNanos }
        frames++
        if (sourceMs != source) {
            sourceUpdates++
            maxSourceHold = maxOf(maxSourceHold, nowNanos - sourceAt)
            sourceAt = nowNanos
        }
        if (visualMs != visual) visualUpdates++
        source = sourceMs
        visual = visualMs
        if (nowNanos - since >= 2_000_000_000L) {
            Timber.tag("LyricsClock").i("samples=%d sourceUpdates=%d visualUpdates=%d maxSourceHoldMs=%d",
                frames, sourceUpdates, visualUpdates, maxOf(maxSourceHold, nowNanos - sourceAt) / 1_000_000L)
            since = nowNanos
            frames = 0; sourceUpdates = 0; visualUpdates = 0; maxSourceHold = 0L
        }
    }
}
