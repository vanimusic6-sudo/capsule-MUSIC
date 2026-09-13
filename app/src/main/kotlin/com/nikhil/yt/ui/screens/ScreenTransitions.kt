package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/**
 * Route navigation deliberately does not move whole pages.
 *
 * A full-width slide makes one screen physically shove the previous screen away, which is exactly
 * the motion language Capsule no longer wants. Individual destinations own their entrance motion
 * instead (hero, controls, sections, rows), so navigation stays interruptible and never needs to
 * wait for a page-sized transition to settle.
 */
internal object ScreenTransitions {
    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        EnterTransition.None

    @Suppress("UNUSED_PARAMETER")
    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        ExitTransition.None
}
