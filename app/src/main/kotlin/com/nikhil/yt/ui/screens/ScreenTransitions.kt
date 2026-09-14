package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/**
 * Route changes are instant, and that is a stability decision as much as a visual one.
 *
 * A timed route transition keeps both destinations composed for its whole duration, and a delay in
 * front of the enter extends the whole `AnimatedContent` transition rather than just postponing the
 * incoming screen. The previous version held the outgoing destination alive for ~290ms on every
 * navigation, which cost three things at once:
 *
 * - a hitch on each tab switch, because two full destinations were composed and drawn together;
 * - transitions that felt different per tab, because the cost depended on what each screen builds;
 * - a crash. Once an entry is destroyed, touching its ViewModels throws, and a destination that is
 *   still composed 290ms after it was popped will recompose inside that window. Rapid tab switching
 *   destroys entries while they are still on screen, and that is exactly the
 *   `IllegalStateException: You cannot access the NavBackStackEntry's ViewModels after the
 *   NavBackStackEntry is destroyed` seen on device.
 *
 * With no transition the outgoing destination is gone within a frame, so the window closes.
 *
 * Overlap is solved where it actually comes from: [CapsuleRouteSurface] gives every destination an
 * opaque canvas. Two screens showing through each other was never a timing problem — transparent
 * screens show through each other no matter how the timing is arranged.
 */
object ScreenTransitions {
    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        EnterTransition.None

    @Suppress("UNUSED_PARAMETER")
    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        ExitTransition.None
}
