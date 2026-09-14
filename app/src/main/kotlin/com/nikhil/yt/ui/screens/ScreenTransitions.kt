package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut

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
 *
 * One exception earns its cost: leaving a settings page. Without an exit the page simply ceases to
 * exist under the screen behind it, which is the one place in the app where a change of screen reads
 * as wooden. Everything else still arrives with the entrance its own destination plays.
 */
object ScreenTransitions {
    /**
     * Long enough to be felt, short enough that the two destinations overlap only briefly.
     *
     * This is the one place a route change costs two composed screens, so it is spent where it is
     * worth the most: leaving a settings page, which otherwise vanishes instantly under the screen
     * behind it and reads as wooden. Ordinary navigation keeps costing one screen.
     */
    const val SettingsExitMillis = 260

    private val Leaving = CubicBezierEasing(0.4f, 0f, 0.7f, 1f)

    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        EnterTransition.None

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition {
        if (!isLeavingSettings(from, to)) return ExitTransition.None

        // The arriving screen plays its own entrance from inside the destination, so this only has
        // to get the settings page out of the way — a fade and a slight recede, which together read
        // as the page stepping back rather than being cut.
        val spec = tween<Float>(SettingsExitMillis, easing = Leaving)
        return fadeOut(spec) + scaleOut(spec, targetScale = SettingsExitScale)
    }

    /**
     * Leaving the settings tree, in either direction: stepping back out of a settings page, and
     * closing settings altogether. Moving deeper into settings is an arrival, and the destination's
     * own entrance already covers that.
     */
    private fun isLeavingSettings(from: String?, to: String?): Boolean {
        val leaving = from?.startsWith("settings") == true
        val arrivingElsewhere = to?.startsWith("settings") != true
        return leaving && (arrivingElsewhere || from.length > (to?.length ?: 0))
    }

    /** A recede, not a shrink: just enough to separate the leaving page from the one behind it. */
    private const val SettingsExitScale = 0.97f
}
