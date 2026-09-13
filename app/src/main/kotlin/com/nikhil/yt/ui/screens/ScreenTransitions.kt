package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Opaque, transform-only navigation motion.
 *
 * Forward navigation lets the destination physically cover the current screen while the
 * underlying screen stays still. Back navigation does the inverse: the current screen moves away
 * and reveals the already-positioned previous screen. There is deliberately no alpha/scale
 * animation here, so two pages never visually blend or dim each other.
 */
internal object ScreenTransitions {
    const val DURATION_MS = 420

    // "Quint-like" ease-out: fast enough at the start to feel responsive, very soft at rest.
    private val motionEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition {
        if (from == to) return EnterTransition.None
        if (isPop) return EnterTransition.None

        val direction = direction(from, to, isPop = false)
        return slideInHorizontally(
            animationSpec = tween(DURATION_MS, easing = motionEasing),
            initialOffsetX = { fullWidth -> direction * fullWidth },
        )
    }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition {
        if (from == to) return ExitTransition.None
        if (!isPop) return ExitTransition.None

        val direction = direction(from, to, isPop = true)
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
