package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.snap

/**
 * Route hand-off is intentionally invisible.
 *
 * Returning EnterTransition.None/ExitTransition.None can leave both destinations active together
 * during fast navigation, while an identity scale transition still draws both pages for a frame.
 * A snapped alpha transition gives Navigation Compose a real transition boundary and immediately
 * makes the outgoing destination invisible. There is no perceptible page fade, slide or scale;
 * only destination-owned element motion is visible.
 */
internal object ScreenTransitions {
    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        fadeIn(animationSpec = snap())

    @Suppress("UNUSED_PARAMETER")
    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        fadeOut(animationSpec = snap())
}
