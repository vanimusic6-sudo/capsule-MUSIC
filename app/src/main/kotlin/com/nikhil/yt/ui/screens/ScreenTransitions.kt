package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Capsule page motion is intentionally opaque and geometry-only.
 *
 * Both pages travel with the same duration and easing. Their touching edges therefore remain
 * locked together for the whole transition: the destination cannot visually overlap the source,
 * and no alpha/scrim/blur is needed to hide the seam. The curve starts from rest, builds momentum,
 * then docks very slowly during the final part of the travel for a dense, "magnetic" feel.
 */
internal object ScreenTransitions {
    const val DURATION_MS = 520

    private val motionEasing = CubicBezierEasing(0.24f, 0f, 0.05f, 1f)

    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition {
        if (from == to) return EnterTransition.None

        val direction = direction(from, to, isPop)
        return slideInHorizontally(
            animationSpec = tween(DURATION_MS, easing = motionEasing),
            initialOffsetX = { fullWidth -> direction * fullWidth },
        )
    }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition {
        if (from == to) return ExitTransition.None

        val direction = direction(from, to, isPop)
        return slideOutHorizontally(
            animationSpec = tween(DURATION_MS, easing = motionEasing),
            targetOffsetX = { fullWidth -> -direction * fullWidth },
        )
    }

    private fun direction(from: String?, to: String?, isPop: Boolean): Int {
        if (from == to) return 0

        val fromIndex = Screens.MainScreens.indexOfFirst { it.route == from }
        val toIndex = Screens.MainScreens.indexOfFirst { it.route == to }

        return if (fromIndex >= 0 && toIndex >= 0) {
            if (toIndex > fromIndex) 1 else -1
        } else if (isPop) {
            -1
        } else {
            1
        }
    }
}
