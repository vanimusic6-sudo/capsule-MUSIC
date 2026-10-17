package com.nikhil.yt.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Short one-shot dialog arrival: a restrained, almost critically damped scale,
 * with just enough spring to give a soft landing.
 *
 * No blurred full-screen surface, no animateContentSize and no idle loop.
 * Compose's animation clock respects system-wide duration scale / reduced motion.
 */
@Composable
fun Modifier.capsuleDialogEntrance(): Modifier {
    val scale = remember { Animatable(0.957f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.88f, stiffness = 400f),
        )
    }
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
