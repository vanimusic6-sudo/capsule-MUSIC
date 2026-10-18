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
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow

/**
 * A single low-cost graphicsLayer transforms popup entrance and separate exit.
 * CapsuleMotionDialog holds distinct, finite opening and departing progress
 * values; no per-frame remeasurement, additional animation clocks or blur.
 */
@Composable
fun Modifier.capsuleDialogEntrance(): Modifier {
    val parentProgress = LocalCapsuleModalProgress.current
    val exitProgress = LocalCapsuleModalExit.current
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
        val leaving = exitProgress?.value ?: 0f
        // Keep the much-liked spring entrance completely unchanged.
        // Dismissal is NOT entrance reversed and NOT a plain alpha fade:
        // a tiny initial retreat, then a 21dp soft drop with opacity reaching
        // zero before the Dialog is removed. One finite Animatable; all visual
        // values below are sampled only in graphicsLayer's draw phase.
        val down = ((leaving - 0.16f) / 0.84f).coerceIn(0f, 1f).pow(1.10f)
        val lift = -2.5f * sin(PI.toFloat() * (leaving / 0.20f).coerceIn(0f, 1f))
        val departureAlpha =
            (1f - ((leaving - 0.12f) / 0.88f).coerceIn(0f, 1f).pow(1.24f))
                .coerceIn(0f, 1f)
        val sizeFactor = (1f - remaining * 0.065f) * (1f - down * 0.022f)
        scaleX = sizeFactor
        scaleY = sizeFactor
        translationY = (remaining * 16f + lift + down * 21f).dp.toPx()
        alpha = departureAlpha
        transformOrigin = TransformOrigin(0.5f, 0.85f)
    }
}
