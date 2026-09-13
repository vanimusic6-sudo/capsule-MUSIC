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
 * Whole routes stay visually stationary. Individual pieces arrive with a short lift and tiny depth
 * response. Motion is intentionally clamped at the resting pose: the destination should feel soft
 * and assembled, never rubbery or like a stack of cards bouncing past its final geometry.
 */
@Stable
class CapsuleSceneMotionState internal constructor(
    internal val progress: Animatable<Float, AnimationVector1D>,
) {
    internal fun itemProgress(order: Int): Float {
        // A very small overlapping stagger is enough to separate hierarchy without making the user
        // wait for a long cascade. Long lists converge quickly instead of rippling for a second.
        val start = (order.coerceAtLeast(0) * 0.022f).coerceAtMost(0.18f)
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
                dampingRatio = 0.90f,
                stiffness = 220f,
            ),
        )
    }

    return state
}

fun Modifier.capsuleSceneItem(
    state: CapsuleSceneMotionState,
    order: Int,
    lift: Dp = 11.dp,
    depth: Float = 0.0055f,
): Modifier =
    graphicsLayer {
        val phase = state.itemProgress(order)
        val residual = 1f - phase

        translationY = residual * lift.toPx()
        scaleX = 1f - residual * (depth * 0.36f)
        scaleY = 1f - residual * depth
        transformOrigin = TransformOrigin(0.5f, 0.42f)
    }
