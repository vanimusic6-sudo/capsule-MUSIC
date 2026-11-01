package com.nikhil.yt.ui.utils

import kotlin.math.roundToInt

/** Stateless and bounded mapping between a vertical scrollbar thumb and list items. */
internal object CapsuleScrollbarMath {
    fun lastStartingIndex(totalItems: Int, visibleItems: Int): Int =
        (totalItems.coerceAtLeast(0) - visibleItems.coerceAtLeast(0)).coerceAtLeast(0)

    fun fractionForIndex(
        firstIndex: Int,
        firstOffsetPx: Int,
        firstItemSizePx: Int,
        lastStartingIndex: Int,
    ): Float {
        if (lastStartingIndex <= 0) return 0f
        val fractional = firstIndex.coerceAtLeast(0).toFloat() +
            (firstOffsetPx.toFloat() / firstItemSizePx.coerceAtLeast(1))
        return (fractional / lastStartingIndex).coerceIn(0f, 1f)
    }

    fun indexForFraction(fraction: Float, lastStartingIndex: Int): Int {
        if (!fraction.isFinite() || lastStartingIndex <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * lastStartingIndex).roundToInt()
            .coerceIn(0, lastStartingIndex)
    }

    fun thumbTop(fraction: Float, viewportPx: Float, thumbPx: Float): Float {
        if (!viewportPx.isFinite() || viewportPx <= 0f || !fraction.isFinite()) return 0f
        val travel = (viewportPx - thumbPx.coerceAtLeast(0f)).coerceAtLeast(0f)
        return fraction.coerceIn(0f, 1f) * travel
    }

    fun fractionForTouch(centerY: Float, viewportPx: Float, thumbPx: Float): Float {
        val travel = viewportPx - thumbPx.coerceAtLeast(0f)
        if (!centerY.isFinite() || !travel.isFinite() || travel <= 0f) return 0f
        return ((centerY - thumbPx / 2f) / travel).coerceIn(0f, 1f)
    }
}
