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
 * The destination canvas itself never moves. Only real controls receive this modifier. Motion is a
 * restrained lift + tiny depth settle with a heavily overlapping stagger, so the screen reads as one
 * composition assembling rather than cards flying over another route.
 */
@Stable
class CapsuleSceneMotionState internal constructor(
    internal val progress: Animatable<Float, AnimationVector1D>,
) {
    internal fun itemProgress(order: Int): Float {
        val start = (order.coerceAtLeast(0) * 0.016f).coerceAtMost(0.12f)
        return ((progress.value - start) / (1f - start)).coerceIn(0f, 1f)
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
                dampingRatio = 0.93f,
                stiffness = 175f,
            ),
        )
    }

    return state
}

fun Modifier.capsuleSceneItem(
    state: CapsuleSceneMotionState,
    order: Int,
    lift: Dp = 8.dp,
    depth: Float = 0.0038f,
): Modifier =
    graphicsLayer {
        val phase = state.itemProgress(order)
        val residual = 1f - phase

        translationY = residual * lift.toPx()
        scaleX = 1f - residual * (depth * 0.30f)
        scaleY = 1f - residual * depth
        transformOrigin = TransformOrigin(0.5f, 0.5f)
    }
