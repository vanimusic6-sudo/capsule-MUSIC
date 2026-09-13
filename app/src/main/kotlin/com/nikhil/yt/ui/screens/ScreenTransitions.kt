package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/**
 * Top-level destinations swap their opaque canvases directly.
 *
 * Capsule motion belongs to controls and content inside the destination, not to the whole screen.
 * Keeping route motion at None prevents two full pages from sliding over each other while preserving
 * immediate, interruptible input. MainTabNavigator settles ComposeNavigator's invisible transition
 * bookkeeping on the following frame so lifecycle state still reaches RESUMED deterministically.
 */
internal object ScreenTransitions {
    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        EnterTransition.None

    @Suppress("UNUSED_PARAMETER")
    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        ExitTransition.None
}
