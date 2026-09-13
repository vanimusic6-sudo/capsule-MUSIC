package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween

/**
 * Route hand-off is intentionally invisible.
 *
 * Navigation Compose still gets a real finite transition so the outgoing destination can complete
 * its lifecycle and be disposed. The alpha hand-off lasts only 1 ms: it is not perceptible as a
 * screen fade, but unlike an identity scale transition it makes the outgoing page visually disappear
 * instead of leaving two translucent destinations drawn together for a frame.
 */
internal object ScreenTransitions {
    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis = 1))

    @Suppress("UNUSED_PARAMETER")
    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = 1))
}
