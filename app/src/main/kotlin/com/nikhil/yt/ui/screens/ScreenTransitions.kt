package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Capsule page motion is opaque, transform-only and spring driven.
 *
 * Both pages use the exact same spring so their touching edges stay locked together. Lower natural
 * frequency gives the eye time to read acceleration and mass, while the slightly under-damped settle
 * preserves the tiny magnetic return that keeps the transition alive instead of sterile.
 */
internal object ScreenTransitions {
    private const val DAMPING_RATIO = 0.82f
    private const val STIFFNESS = 185f

    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition {
        if (from == to) return EnterTransition.None

        val direction = direction(from, to, isPop)
        return slideInHorizontally(
            animationSpec =
                spring(
                    dampingRatio = DAMPING_RATIO,
                    stiffness = STIFFNESS,
                ),
            initialOffsetX = { fullWidth -> direction * fullWidth },
        )
    }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition {
        if (from == to) return ExitTransition.None

        val direction = direction(from, to, isPop)
        return slideOutHorizontally(
            animationSpec =
                spring(
                    dampingRatio = DAMPING_RATIO,
                    stiffness = STIFFNESS,
                ),
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