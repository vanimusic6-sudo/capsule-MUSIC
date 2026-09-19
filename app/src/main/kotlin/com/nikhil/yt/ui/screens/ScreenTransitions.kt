package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/**
 * Route changes are instant. No destination is ever drawn on top of another one.
 *
 * A timed route transition keeps both destinations composed for its whole duration, and that costs
 * three things at once:
 *
 * - a hitch on each tab switch, because two full destinations are composed and drawn together;
 * - transitions that feel different per tab, because the cost depends on what each screen builds;
 * - a crash. Once an entry is destroyed, touching its ViewModels throws, and a destination still
 *   composed 290ms after it was popped will recompose inside that window. Rapid tab switching
 *   destroys entries while they are still on screen, and that is exactly the
 *   `IllegalStateException: You cannot access the NavBackStackEntry's ViewModels after the
 *   NavBackStackEntry is destroyed` seen on device.
 *
 * With no transition the outgoing destination is gone within a frame, so the window closes.
 *
 * There used to be one exception: leaving a settings page faded and slid out, so that stepping back
 * out of the tree read as movement rather than a page ceasing to exist. It is gone, and it is worth
 * saying why, because the reasoning applies to any future exit. An exit transition is by definition
 * two destinations on screen at once, and a *fading* exit is by definition the screen behind showing
 * through the one leaving. However brief and however slight the opacity, that is one screen pasted
 * over another, which is the single effect this app does not want anywhere.
 *
 * The feel it was there for is kept, and kept honestly: leaving a settings page is now expressed by
 * the page that *arrives*, which slides in from the leading edge — the direction you came from —
 * instead of the trailing edge it uses going deeper. One screen moves, one screen is on display, and
 * the pair still reads as a step along a path. See [DestinationMotion.Settings].
 */
object ScreenTransitions {
    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        EnterTransition.None

    @Suppress("UNUSED_PARAMETER")
    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        ExitTransition.None
}
