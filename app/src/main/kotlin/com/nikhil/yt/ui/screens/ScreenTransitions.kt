package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

/**
 * Motion policy audited after the repeated hard / crooked settings transitions.
 *
 * WHY ROOT USED TO LOOK WRONG:
 * The previous root offset +X,-Y AND a top-right scale ran as distinct
 * transitions. Transform origin already caused the sheet's left/bottom
 * edges to travel from the top-right. Adding a second diagonal translation
 * pulled the entire canvas AWAY from its apparent launch point, causing
 * clipped edges, large empty bands and a hard correction at the end.
 *
 * FIX: one top-right *scale* with a single near-critically damped spring.
 * One physical coordinate system; the sheet grows down and left from
 * precisely the same corner, and shrinks along the identical path on Back.
 * The previous page is still composed behind the opaque settings canvas.
 *
 * Inside settings: one pair of finite, identical-clock sideways tweens.
 * The same full-width distance in both directions, no overlapping
 * scale, opacity, bounce or open-ended spring tail to keep two complex
 * Compose settings trees drawing after the user perceives the motion end.
 *
 * Never enable these real NavHost transitions globally. Previous work
 * crashed destroyed NavBackStackEntry ViewModels under rapid tab switching.
 */
private fun String?.isSettingsPage(): Boolean =
    this == "settings" || this?.startsWith("settings/") == true

private val TopRight = TransformOrigin(1f, 0f)

// Root starts visibly smaller: at the top-right control position it grows
// down and left without diagonal overshoot or conflicting clocks.
internal const val SettingsRootInitialScale = 0.68f
private val RootOpening = spring<Float>(
    dampingRatio = 0.95f,
    stiffness = 210f,
    visibilityThreshold = 0.001f,
)
private val RootClosing = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 265f,
    visibilityThreshold = 0.001f,
)

// Nested transitions are bounded and matched. Their cost ends at 340ms;
// the former low-stiffness spring continued rendering heavy two-page frames.
private val NestedTravel = tween<IntOffset>(
    durationMillis = 340,
    easing = CapsuleStandardEasing,
)

internal data class SettingsPageTravel(
    val startOffsetFraction: Float,
    val startScale: Float,
)
private val RootTravel = SettingsPageTravel(startOffsetFraction = 0f, startScale = SettingsRootInitialScale)
private val DetailTravel = SettingsPageTravel(startOffsetFraction = 1f, startScale = 1f)
internal fun settingsPageTravel(from: String?): SettingsPageTravel =
    if (from.isSettingsPage()) DetailTravel else RootTravel

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
            SettingsRouteMotion.NESTED -> slideInHorizontally(
                initialOffsetX = { if (isPop) -it else it },
                animationSpec = NestedTravel,
            )
            SettingsRouteMotion.ROOT -> {
                if (isPop || !to.isSettingsPage()) EnterTransition.None
                else scaleIn(
                    initialScale = SettingsRootInitialScale,
                    transformOrigin = TopRight,
                    animationSpec = RootOpening,
                )
            }
        }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        when (settingsRouteMotion(from, to)) {
            SettingsRouteMotion.NONE -> ExitTransition.None
            SettingsRouteMotion.NESTED -> slideOutHorizontally(
                targetOffsetX = { if (isPop) it else -it },
                animationSpec = NestedTravel,
            )
            SettingsRouteMotion.ROOT -> {
                if (!isPop || !from.isSettingsPage()) ExitTransition.None
                else scaleOut(
                    targetScale = SettingsRootInitialScale,
                    transformOrigin = TopRight,
                    animationSpec = RootClosing,
                )
            }
        }
}
