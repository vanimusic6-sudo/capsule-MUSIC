package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

internal const val MiniPlayerMaxSwipeTilt = 2.5f
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
    val rotation = (physicalOffset / (widthPx * 0.5f)).coerceIn(-1f, 1f) * MiniPlayerMaxSwipeTilt
    // A shallow arc keeps the raised corner inside the bottom sheet's top edge.
    val drop = widthPx * 0.5f * abs(sin(rotation * PI / 180)).toFloat()
    return MiniPlayerSwipeTransform(physicalOffset, drop, rotation)
}

internal fun Modifier.miniPlayerSwipeMotion(offset: () -> Float, layoutDirection: LayoutDirection) = graphicsLayer {
    val motion = miniPlayerSwipeTransform(offset(), size.width, layoutDirection == LayoutDirection.Rtl)
    translationX = motion.x
    translationY = motion.y
    rotationZ = motion.rotation
    transformOrigin = TransformOrigin(0.5f, 1f)
}
