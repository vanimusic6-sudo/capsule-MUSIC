/* Capsule MUSIC — bounded detection of baked-in black borders. GPL-3.0. */
package com.nikhil.yt.ui.player

import kotlin.math.abs
import kotlin.math.max

internal data class CapsuleVideoInsets(val horizontal: Float = 0f, val vertical: Float = 0f) {
    fun zoom(sourceAspect: Float, viewportAspect: Float): Float {
        if (sourceAspect <= 0f || viewportAspect <= 0f) return 1f
        val base = max(viewportAspect / sourceAspect, 1f)
        val cropped = max(viewportAspect / (sourceAspect * (1f - 2f * horizontal)),
            1f / (1f - 2f * vertical))
        return (cropped / base).coerceIn(1f, 1.6f)
    }
}

/** Inspect only tiny startup snapshots, never run a filter on decoded frames. */
internal fun detectCapsuleVideoInsets(width: Int, height: Int, pixel: (Int, Int) -> Int): CapsuleVideoInsets? {
    if (width < 32 || height < 32) return null
    fun brightness(x: Int, y: Int): Int {
        val rgb = pixel(x, y)
        return max((rgb ushr 16) and 255, max((rgb ushr 8) and 255, rgb and 255))
    }
    var bright = 0
    var samples = 0
    for (y in height / 3 until height * 2 / 3 step 2) {
        for (x in width / 3 until width * 2 / 3 step 2) {
            if (brightness(x, y) >= 45) bright++
            samples++
        }
    }
    // Fades to black/dark scenes are not a reliable border measurement.
    if (bright < samples / 4) return null
    fun darkRow(y: Int) = (0 until width).count { brightness(it, y) > 20 } <= width / 100
    fun darkColumn(x: Int) = (0 until height).count { brightness(x, it) > 20 } <= height / 100
    fun band(length: Int, dark: (Int) -> Boolean): Float {
        val limit = (length * 0.23f).toInt()
        val start = (0 until limit).takeWhile(dark).size
        val end = (0 until limit).takeWhile { dark(length - 1 - it) }.size
        if (start < 2 || end < 2 || start == limit || end == limit ||
            abs(start - end) > max(2, minOf(start, end) / 5)) return 0f
        return (minOf(start, end) - 1).coerceAtLeast(0).toFloat() / length
    }
    return CapsuleVideoInsets(band(width, ::darkColumn), band(height, ::darkRow))
}

/** Two agreeing illuminated frames prevent an intro/fade from zooming a clip. */
internal class CapsuleVideoBorderConsensus {
    private var previous: CapsuleVideoInsets? = null
    fun offer(next: CapsuleVideoInsets?): CapsuleVideoInsets? {
        next ?: return null
        val old = previous
        previous = next
        if (old == null || abs(old.horizontal - next.horizontal) > 0.025f ||
            abs(old.vertical - next.vertical) > 0.025f) return null
        return CapsuleVideoInsets(minOf(old.horizontal, next.horizontal), minOf(old.vertical, next.vertical))
    }
}
