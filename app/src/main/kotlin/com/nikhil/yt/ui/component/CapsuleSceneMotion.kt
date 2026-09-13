package com.nikhil.yt.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
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
 * Small scene-level motion shared by destination content.
 *
 * The route itself never slides. Instead, visible pieces settle independently with a short vertical
 * lift, tiny compression and a restrained spring return. There is deliberately no alpha animation,
 * blur or full-screen transform here.
 */
@Stable
class CapsuleSceneMotionState internal constructor(
    internal val progress: Animatable<Float, AnimationVector1D>,
) {
    internal fun itemProgress(order: Int): Float {
        val start = (order.coerceAtLeast(0) * 0.055f).coerceAtMost(0.28f)
        return ((progress.value - start) / (1f - start)).coerceIn(0f, 1.055f)
    }
}

@Composable
fun rememberCapsuleSceneMotionState(key: Any? = Unit): CapsuleSceneMotionState {
    val state = remember(key) {
        CapsuleSceneMotionState(Animatable(0f))
    }

    LaunchedEffect(state, key) {
        state.progress.snapTo(0f)
        state.progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = 150f,
            ),
        )
    }

    return state
}

fun Modifier.capsuleSceneItem(
    state: CapsuleSceneMotionState,
    order: Int,
    lift: Dp = 18.dp,
    depth: Float = 0.010f,
): Modifier =
    graphicsLayer {
        val phase = state.itemProgress(order)
        val residual = 1f - phase

        translationY = residual * lift.toPx()
        scaleX = 1f - residual * (depth * 0.45f)
        scaleY = 1f - residual * depth
        transformOrigin = TransformOrigin(0.5f, 0.35f)
    }
