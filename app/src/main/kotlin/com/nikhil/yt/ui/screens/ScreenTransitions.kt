package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset

/**
 * Capsule settings navigation: a single physical page with a right-side origin.
 *
 * IMPORTANT: the entire settings destination (its opaque canvas + content) owns ONE
 * NavHost transition. Never animate it a second time in CapsuleRouteSurface or
 * assign alpha to an opaque route: the previous page is real content below it.
 *
 * The root page grows from the vicinity of the top-right Settings action.
 * Detail pages enter from the right, much like the lyrics ink aperture,
 * but remain opaque throughout: no grey seams, nested fades, or text delays.
 *
 * Other routes NEVER animate both destinations. Keeping two heavy artwork
 * destinations composed caused an actual back-stack/ViewModel lifecycle crash.
 */
private fun String?.isSettingsPage(): Boolean =
    this == "settings" || this?.startsWith("settings/") == true

private val SettingsCorner = TransformOrigin(pivotFractionX = 1f, pivotFractionY = 0.09f)

// The sizes and travel share the SAME spring. Mismatched independent clocks
// are the cause of the "knock" at the end when scale settles before travel.
private val PageArriveOffset = spring<IntOffset>(
    dampingRatio = 0.88f,
    stiffness = Spring.StiffnessMediumLow,
)
private val PageArriveScale = spring<Float>(
    dampingRatio = 0.88f,
    stiffness = Spring.StiffnessMediumLow,
)
private val PageLeaveOffset = spring<IntOffset>(
    dampingRatio = 0.94f,
    stiffness = Spring.StiffnessMedium,
)
private val PageLeaveScale = spring<Float>(
    dampingRatio = 0.94f,
    stiffness = Spring.StiffnessMedium,
)

// Reversible geometry, not pixel-by-pixel delays or competing fades.
internal data class SettingsPageTravel(
    val startOffsetFraction: Float,
    val startScale: Float,
)

// A full screen is entering from the Settings button side. It begins mostly
// outside the viewport while it is small, and grows as its leading edge crosses.
private val SettingsRootTravel = SettingsPageTravel(
    startOffsetFraction = 0.88f,
    startScale = 0.76f,
)

// Inside settings, category pages behave more like opening lyrics:
// a lateral page reveal with a tiny perspective-like expansion.
private val SettingsDetailTravel = SettingsPageTravel(
    startOffsetFraction = 0.73f,
    startScale = 0.94f,
)

internal fun settingsPageTravel(from: String?): SettingsPageTravel =
    if (from.isSettingsPage()) SettingsDetailTravel else SettingsRootTravel

object ScreenTransitions {
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition {
        if (isPop || !to.isSettingsPage()) return EnterTransition.None

        val travel = settingsPageTravel(from)
        return slideInHorizontally(
            initialOffsetX = { width -> (width * travel.startOffsetFraction).toInt() },
            animationSpec = PageArriveOffset,
        ) + scaleIn(
            initialScale = travel.startScale,
            transformOrigin = SettingsCorner,
            animationSpec = PageArriveScale,
        )
    }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition {
        if (!isPop || !from.isSettingsPage()) return ExitTransition.None

        // A page going Back retraces the SAME geometry it arrived along.
        // Its parent/home is not animated, only uncovered below the page.
        val travel = if (to.isSettingsPage()) SettingsDetailTravel else SettingsRootTravel
        return slideOutHorizontally(
            targetOffsetX = { width -> (width * travel.startOffsetFraction).toInt() },
            animationSpec = PageLeaveOffset,
        ) + scaleOut(
            targetScale = travel.startScale,
            transformOrigin = SettingsCorner,
            animationSpec = PageLeaveScale,
        )
    }
}
