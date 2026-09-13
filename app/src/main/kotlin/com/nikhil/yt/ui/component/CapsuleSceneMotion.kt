package com.nikhil.yt.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Slow, low-amplitude scene motion for destination-owned controls.
 *
 * Whole destinations never move. A scene starts once when its destination is composed and controls
 * settle over a shared long curve with a very small overlapping stagger. The curve starts and ends
 * with near-zero velocity so short 3-5 dp movements read as weight, not as a snap. A deterministic
 * tween also avoids the frame-pacing sensitivity that made the previous small spring movements feel
 * nervous on real devices.
 */
@Stable
class CapsuleSceneMotionState internal constructor(
    internal val progress: Animatable<Float, AnimationVector1D>,
) {
    internal fun itemProgress(order: Int): Float {
        val start = (order.coerceAtLeast(0) * 0.018f).coerceAtMost(0.12f)
        return ((progress.value - start) / (1f - start)).coerceIn(0f, 1f)
    }
}

private val CapsuleSceneEasing = CubicBezierEasing(0.22f, 0f, 0.18f, 1f)

@Composable
fun rememberCapsuleSceneMotionState(key: Any? = Unit): CapsuleSceneMotionState {
    val state = remember(key) {
        CapsuleSceneMotionState(Animatable(0f))
    }

    LaunchedEffect(state, key) {
        state.progress.snapTo(0f)
        state.progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 700,
                easing = CapsuleSceneEasing,
            ),
        )
    }

    return state
}

fun Modifier.capsuleSceneItem(
    state: CapsuleSceneMotionState,
    order: Int,
    lift: Dp = 5.dp,
    depth: Float = 0.0018f,
): Modifier =
    graphicsLayer {
        val phase = state.itemProgress(order)
        val residual = 1f - phase

        translationY = residual * lift.toPx()
        scaleX = 1f - residual * (depth * 0.22f)
        scaleY = 1f - residual * depth
        transformOrigin = TransformOrigin(0.5f, 0.46f)
    }
