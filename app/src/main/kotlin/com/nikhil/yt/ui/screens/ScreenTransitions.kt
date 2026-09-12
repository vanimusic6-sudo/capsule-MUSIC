package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Short travel keeps interrupted transitions inside the viewport. */
internal object ScreenTransitions {
    const val DURATION_MS = 240

    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition {
        val direction = direction(from, to, isPop)
        return fadeIn(tween(DURATION_MS, easing = LinearOutSlowInEasing)) +
            slideInHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { direction * it / 12 }
    }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition {
        val direction = direction(from, to, isPop)
        return fadeOut(tween(160)) +
            slideOutHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { -direction * it / 24 }
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
