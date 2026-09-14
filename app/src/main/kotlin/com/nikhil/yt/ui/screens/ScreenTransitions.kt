package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Route motion, sequenced so two destinations are never on screen together.
 *
 * The overlap people could catch on a Back press was not a timing accident: with both transitions
 * at None, `AnimatedContent` holds the outgoing destination at full opacity while the incoming one
 * is already composed, and destinations do not paint an opaque canvas, so the two show through each
 * other. Tying the incoming animation to the button press cannot fix that, because the press is not
 * what decides when the outgoing destination is done.
 *
 * So the incoming transition is tied to the outgoing one instead: every enter is delayed by exactly
 * the exit's duration. The outgoing destination has reached zero opacity before the incoming one
 * starts drawing, which makes a caught overlap arithmetically impossible rather than unlikely.
 *
 * Only the settings flow slides. Elsewhere a route change is a plain sequenced fade — no screen
 * travels across another, which is the rule for the rest of the app.
 */
object ScreenTransitions {
    /** The outgoing destination's whole life. Kept short: nothing waits on it but the next screen. */
    const val ExitMillis = 110

    /** The incoming destination, starting the instant the outgoing one is gone. */
    const val EnterMillis = 180

    /** Every enter waits out the whole exit. This is what makes an overlap impossible. */
    const val EnterDelayMillis = ExitMillis

    /** A shift, not a push: enough to read as moving between screens, far from a full-width slide. */
    private const val SettingsShift = 0.16f

    private val Leaving = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    private val Arriving = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition {
        val fade = fadeIn(tween(EnterMillis, delayMillis = EnterDelayMillis, easing = Arriving))
        if (!isSettingsFlow(from, to)) return fade

        return fade +
            slideInHorizontally(
                animationSpec = tween(EnterMillis, delayMillis = EnterDelayMillis, easing = Arriving),
            ) { width -> settingsShiftPx(width, towardsStart = isPop) }
    }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition {
        val fade = fadeOut(tween(ExitMillis, easing = Leaving))
        if (!isSettingsFlow(from, to)) return fade

        return fade +
            slideOutHorizontally(
                animationSpec = tween(ExitMillis, easing = Leaving),
            ) { width -> settingsShiftPx(width, towardsStart = !isPop) }
    }

    /**
     * Going deeper, the new screen arrives from the trailing edge and the old one leaves towards the
     * leading edge. A Back press mirrors both, so the flow reads as stepping back out.
     */
    private fun settingsShiftPx(width: Int, towardsStart: Boolean): Int {
        val shift = (width * SettingsShift).toInt().coerceAtLeast(0)
        return if (towardsStart) -shift else shift
    }

    private fun isSettingsFlow(from: String?, to: String?): Boolean =
        from?.startsWith("settings") == true && to?.startsWith("settings") == true
}
