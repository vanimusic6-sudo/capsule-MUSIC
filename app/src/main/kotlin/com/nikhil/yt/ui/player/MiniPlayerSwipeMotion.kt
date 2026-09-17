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

/**
 * The swipe itself stays 1:1 with the finger; this spring is used only after release/cancel.
 * A slightly under-critical return keeps the existing physical feel without the hard stop that the
 * old medium-low/no-bounce spring produced on high-refresh displays.
 */
internal val MiniPlayerSwipeSpring =
    spring<Float>(
        dampingRatio = 0.92f,
        stiffness = Spring.StiffnessLow,
    )

internal data class MiniPlayerSwipeTransform(
    val x: Float,
    val y: Float,
    val rotation: Float,
)

internal fun miniPlayerSwipeTransform(
    offsetPx: Float,
    widthPx: Float,
    isRtl: Boolean = false,
): MiniPlayerSwipeTransform {
    if (!offsetPx.isFinite() || !widthPx.isFinite() || widthPx <= 0f) {
        return MiniPlayerSwipeTransform(0f, 0f, 0f)
    }

    val physicalOffset = if (isRtl) -offsetPx else offsetPx
    val normalizedOffset =
        (physicalOffset / (widthPx * 0.5f))
            .coerceIn(-1f, 1f)
    val rotation = normalizedOffset * MiniPlayerMaxSwipeTilt

    /*
     * Preserve the same shallow bottom-pivot arc and the same peak drop, but use quintic
     * smootherstep rather than cubic smoothstep. Velocity *and acceleration* are zero at both the
     * resting point and the clamped edge, so crossing centre or finishing a hard swipe cannot create
     * a tiny vertical kick. This remains pure geometry: no alpha, blur or extra animation clock.
     */
    val distance = abs(normalizedOffset)
    val arc =
        distance * distance * distance *
            (distance * (distance * 6f - 15f) + 10f)
    val drop = widthPx * MiniPlayerMaxSwipeDropFraction * arc

    return MiniPlayerSwipeTransform(
        x = physicalOffset,
        y = drop,
        rotation = rotation,
    )
}

internal fun Modifier.miniPlayerSwipeMotion(
    offset: () -> Float,
    layoutDirection: LayoutDirection,
) = graphicsLayer {
    val motion =
        miniPlayerSwipeTransform(
            offsetPx = offset(),
            widthPx = size.width,
            isRtl = layoutDirection == LayoutDirection.Rtl,
        )
    translationX = motion.x
    translationY = motion.y
    rotationZ = motion.rotation
    transformOrigin = TransformOrigin(0.5f, 1f)
}
