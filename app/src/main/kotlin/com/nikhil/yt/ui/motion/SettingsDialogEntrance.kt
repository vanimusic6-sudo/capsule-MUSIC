package com.nikhil.yt.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * One draw-phase transform for both appearing and departing.
 *
 * Inside CapsuleMotionDialog, the parent owns a single 0->1->0
 * progress clock; the dialog remains composed until reverse motion ends.
 * Standalone AlertDialogs retain their existing one-shot entrance.
 * No alpha-only exit, no per-frame measure, no animation of text separately.
 */
@Composable
fun Modifier.capsuleDialogEntrance(): Modifier {
    val parentProgress = LocalCapsuleModalProgress.current
    val localProgress = remember { Animatable(0f) }
    if (parentProgress == null) {
        LaunchedEffect(localProgress) {
            localProgress.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = 0.93f,
                    stiffness = 290f,
                    visibilityThreshold = 0.001f,
                ),
            )
        }
    }
    val animation = parentProgress ?: localProgress
    return this.graphicsLayer {
        val remaining = 1f - animation.value
        val sizeFactor = 1f - remaining * 0.065f
        scaleX = sizeFactor
        scaleY = sizeFactor
        translationY = remaining * 16.dp.toPx()
        transformOrigin = TransformOrigin(0.5f, 0.85f)
    }
}
