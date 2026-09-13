package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/**
 * Route hand-off itself never animates.
 *
 * Full-screen transitions were the source of both visual stacking and an interrupted-transition
 * lifecycle race when Back was pressed immediately after selecting a top-level tab. Destinations
 * now swap directly; motion belongs only to controls inside the destination via CapsuleSceneMotion.
 */
internal object ScreenTransitions {
    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        EnterTransition.None

    @Suppress("UNUSED_PARAMETER")
    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        ExitTransition.None
}
