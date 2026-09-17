package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

internal const val MiniPlayerMaxSwipeTilt = 2.5f
private const val MiniPlayerMaxSwipeDropFraction = 0.02181f
internal val MiniPlayerSwipeSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

internal data class MiniPlayerSwipeTransform(val x: Float, val y: Float, val rotation: Float)

internal fun miniPlayerSwipeTransform(offsetPx: Float, widthPx: Float, isRtl: Boolean = false): MiniPlayerSwipeTransform {
    if (!offsetPx.isFinite() || !widthPx.isFinite() || widthPx <= 0f) {
        return MiniPlayerSwipeTransform(0f, 0f, 0f)
    }
    val physicalOffset = if (isRtl) -offsetPx else offsetPx
    val normalizedOffset = (physicalOffset / (widthPx * 0.5f)).coerceIn(-1f, 1f)
    val rotation = normalizedOffset * MiniPlayerMaxSwipeTilt

    // Keep the existing shallow bottom-pivot arc, but make its centre C1-continuous. The old
    // abs(sin(rotation)) path had a cusp at zero: crossing the resting position instantly flipped
    // vertical velocity and showed up as a tiny tick on high-refresh displays. Smoothstep of the
    // absolute normalized travel preserves the same peak drop while easing vertical speed to zero
    // at the centre. It also removes a per-frame trigonometric call from the hot drag path.
    val distance = abs(normalizedOffset)
    val arc = distance * distance * (3f - 2f * distance)
    val drop = widthPx * MiniPlayerMaxSwipeDropFraction * arc

    return MiniPlayerSwipeTransform(physicalOffset, drop, rotation)
}

internal fun Modifier.miniPlayerSwipeMotion(offset: () -> Float, layoutDirection: LayoutDirection) = graphicsLayer {
    val motion = miniPlayerSwipeTransform(offset(), size.width, layoutDirection == LayoutDirection.Rtl)
    translationX = motion.x
    translationY = motion.y
    rotationZ = motion.rotation
    transformOrigin = TransformOrigin(0.5f, 1f)
}
