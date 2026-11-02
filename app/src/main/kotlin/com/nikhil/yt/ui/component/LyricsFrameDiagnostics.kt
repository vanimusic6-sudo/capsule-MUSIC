package com.nikhil.yt.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.nikhil.yt.utils.GlobalLog
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/** Opt-in logging of real window frames, not an assumed animation-clock FPS.
 * One listener while Lyrics is visible/playing; no timer or work when logging is off. */
@Composable
internal fun LyricsFrameDiagnostics(enabled: Boolean, style: String) {
    val context = LocalContext.current
    DisposableEffect(context, enabled, style, GlobalLog.isEnabled) {
        val window = context.activity()?.window
        if (!enabled || !GlobalLog.isEnabled || window == null) return@DisposableEffect onDispose { }
        val thread = HandlerThread("capsule-lyrics-frames", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val active = AtomicBoolean(true)
        var first = 0L
        var previous = 0L
        var maxGap = 0L
        var total = 0L
        var layout = 0L
        var draw = 0L
        var frames = 0
        var metricDrops = 0
        val durations = ArrayList<Long>(256)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            if (active.get()) {
                val now = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                if (now > 0L) {
                    if (first == 0L) first = now
                    if (previous > 0L) maxGap = maxOf(maxGap, now - previous)
                    previous = now
                    frames++
                    val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0L)
                    total += duration
                    layout += metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION).coerceAtLeast(0L)
                    draw += metrics.getMetric(FrameMetrics.DRAW_DURATION).coerceAtLeast(0L)
                    metricDrops += dropped
                    if (durations.size < 256) durations += duration
                    if (now - first >= 2_000_000_000L) {
                        durations.sort()
                        val p95 = durations.getOrNull(((durations.size - 1) * 95 / 100).coerceAtLeast(0)) ?: 0L
                        Timber.tag("LyricsFrames").i(
                            "style=%s samples=%d cadenceFps=%.1f totalMs=%.2f p95Ms=%.2f maxGapMs=%.1f layoutMs=%.2f drawMs=%.2f metricDrops=%d",
                            style, frames, (frames - 1) * 1_000_000_000.0 / (now - first),
                            total.toDouble() / frames / 1_000_000.0, p95 / 1_000_000.0,
                            maxGap / 1_000_000.0, layout.toDouble() / frames / 1_000_000.0,
                            draw.toDouble() / frames / 1_000_000.0, metricDrops,
                        )
                        first = 0L; previous = 0L; maxGap = 0L
                        total = 0L; layout = 0L; draw = 0L; frames = 0; metricDrops = 0; durations.clear()
                    }
                }
            }
        }
        val registered = runCatching {
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }.isSuccess
        if (!registered) { active.set(false); thread.quitSafely() }
        onDispose {
            active.set(false)
            if (registered) runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
    else -> null
}
