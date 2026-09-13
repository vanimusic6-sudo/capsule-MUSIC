package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween

/**
 * Route hand-off itself never moves.
 *
 * Navigation Compose can keep the outgoing and incoming destinations composed together for a short
 * transition window. That is useful for page animations, but Capsule deliberately does not animate
 * whole pages. Keep the incoming destination fully present and make the outgoing destination
 * invisible within the same display frame. The 1 ms alpha hand-off is not a visible fade; it is a
 * rendering guard that prevents stale route content from being drawn over the new destination while
 * Navigation finishes its lifecycle bookkeeping.
 */
internal object ScreenTransitions {
    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        EnterTransition.None

    @Suppress("UNUSED_PARAMETER")
    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = 1), targetAlpha = 0f)
}
