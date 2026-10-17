package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Capsule settings motion. Only NavHost owns the page transition.
 * No child alpha, no additional screen graphicsLayer, no blur per frame.
 *
 * ROOT: launch diagonally from ABOVE AND RIGHT (close to the top-right
 * settings action). Both scale and 2-D displacement share damping/frequency,
 * so the page does not stop moving on one axis while another keeps wobbling.
 *
 * NESTED: a genuinely synchronized full-width push. The entering and
 * departing pages cover adjacent portions of the screen at every frame.
 * The header and content travel as one opaque page; no double text.
 *
 * Non-settings destinations stay instant to avoid the old backstack/ViewModel
 * destroyed-entry crash caused by keeping heavyweight artwork pages alive.
 */
private fun String?.isSettingsPage(): Boolean =
    this == "settings" || this?.startsWith("settings/") == true

private val SettingsTopRight = TransformOrigin(1f, 0f)

// Deliberately soft and close to critically damped; no repeated visible bounce.
// Both members of each pair have the SAME spring constants.
private val NestedPush = spring<IntOffset>(
    dampingRatio = 0.98f,
    stiffness = 235f,
)
private val RootInPosition = spring<IntOffset>(
    dampingRatio = 0.90f,
    stiffness = 235f,
)
private val RootInScale = spring<Float>(
    dampingRatio = 0.90f,
    stiffness = 235f,
)
private val RootOutPosition = spring<IntOffset>(
    dampingRatio = 0.96f,
    stiffness = 270f,
)
private val RootOutScale = spring<Float>(
    dampingRatio = 0.96f,
    stiffness = 270f,
)

internal data class SettingsPageTravel(
    val startOffsetFraction: Float,
    val startScale: Float,
)
private val RootTravel = SettingsPageTravel(startOffsetFraction = 0.66f, startScale = 0.80f)
private val DetailTravel = SettingsPageTravel(startOffsetFraction = 1f, startScale = 1f)
internal fun settingsPageTravel(from: String?): SettingsPageTravel =
    if (from.isSettingsPage()) DetailTravel else RootTravel

/** The settings panel originates ABOVE the top-right button, not mid-screen. */
internal fun settingsRootOffset(size: IntSize): IntOffset =
    IntOffset(
        x = (size.width * RootTravel.startOffsetFraction).toInt(),
        y = -(size.height * 0.27f).toInt(),
    )

internal enum class SettingsRouteMotion { NONE, ROOT, NESTED }
internal fun settingsRouteMotion(from: String?, to: String?): SettingsRouteMotion =
    when {
        from.isSettingsPage() && to.isSettingsPage() -> SettingsRouteMotion.NESTED
        from.isSettingsPage() || to.isSettingsPage() -> SettingsRouteMotion.ROOT
        else -> SettingsRouteMotion.NONE
    }

object ScreenTransitions {
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        when (settingsRouteMotion(from, to)) {
            SettingsRouteMotion.NONE -> EnterTransition.None
            SettingsRouteMotion.NESTED -> {
                slideInHorizontally(
                    initialOffsetX = { if (isPop) -it else it },
                    animationSpec = NestedPush,
                )
            }
            SettingsRouteMotion.ROOT -> {
                if (isPop || !to.isSettingsPage()) EnterTransition.None
                else slideIn(
                    initialOffset = ::settingsRootOffset,
                    animationSpec = RootInPosition,
                ) + scaleIn(
                    initialScale = RootTravel.startScale,
                    transformOrigin = SettingsTopRight,
                    animationSpec = RootInScale,
                )
            }
        }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        when (settingsRouteMotion(from, to)) {
            SettingsRouteMotion.NONE -> ExitTransition.None
            SettingsRouteMotion.NESTED -> {
                slideOutHorizontally(
                    targetOffsetX = { if (isPop) it else -it },
                    animationSpec = NestedPush,
                )
            }
            SettingsRouteMotion.ROOT -> {
                if (!isPop || !from.isSettingsPage()) ExitTransition.None
                else slideOut(
                    targetOffset = ::settingsRootOffset,
                    animationSpec = RootOutPosition,
                ) + scaleOut(
                    targetScale = RootTravel.startScale,
                    transformOrigin = SettingsTopRight,
                    animationSpec = RootOutScale,
                )
            }
        }
}
