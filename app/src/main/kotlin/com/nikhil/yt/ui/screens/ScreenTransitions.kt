package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Short travel keeps interrupted transitions inside the viewport. */
internal object ScreenTransitions {
    const val DURATION_MS = 280
    private val motionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val appearEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition {
        val direction = direction(from, to, isPop)
        // Reveal the arriving page early while the shorter movement settles gently.
        return fadeIn(tween(220, easing = appearEasing)) +
            slideInHorizontally(tween(DURATION_MS, easing = motionEasing)) { direction * it / 18 }
    }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition {
        val direction = direction(from, to, isPop)
        return fadeOut(tween(220)) +
            slideOutHorizontally(tween(DURATION_MS, easing = motionEasing)) { -direction * it / 36 }
    }

    private fun direction(from: String?, to: String?, isPop: Boolean): Int {
        if (from == to) return 0
        val fromIndex = Screens.MainScreens.indexOfFirst { it.route == from }
        val toIndex = Screens.MainScreens.indexOfFirst { it.route == to }
        return if (fromIndex >= 0 && toIndex >= 0) {
            if (toIndex > fromIndex) 1 else -1
        } else if (isPop) -1 else 1
    }
}
