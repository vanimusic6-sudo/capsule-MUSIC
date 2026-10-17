package com.nikhil.yt.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/**
 * One finite motion per popup, used by settings choice lists, editor prompts
 * and confirmation dialogs. A small rise and expansion share ONE spring value.
 *
 * The old scale-only effect had no approach and appeared to snap from 95.7%
 * size at a fixed position. The motion now grows from a point just below
 * the final rectangle and settles without a second animation phase.
 *
 * No blur, layout animation, shadow animation, alpha crossfade, extra
 * infinite frame-clock or CPU polling. Only the render layer changes per
 * frame; controls retain stable tap targets and layout bounds.
 */
@Composable
fun Modifier.capsuleDialogEntrance(): Modifier {
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(arrival) {
        arrival.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.93f,
                stiffness = 290f,
                visibilityThreshold = 0.001f,
            ),
        )
    }
    return this.graphicsLayer {
        val progress = arrival.value
        val remaining = 1f - progress
        val sizeFactor = 1f - remaining * 0.065f
        scaleX = sizeFactor
        scaleY = sizeFactor
        translationY = remaining * 16.dp.toPx()
        transformOrigin = TransformOrigin(0.5f, 0.85f)
    }
}
