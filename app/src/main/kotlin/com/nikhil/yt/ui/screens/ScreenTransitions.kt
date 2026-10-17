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
 * Settings motion rebuilt after reviewing recording 9467.mp4 (0–24 seconds).
 *
 * The old implementation entered every settings sub-page with scale+partial
 * horizontal translation, but kept the old screen stationary. A large slice
 * of BOTH screens was readable for most of each transition, creating the
 * "two settings screens crowd into one viewport" defect visible at 8–10 s,
 * 17–20 s and 23–24 s. The scale distorted the incoming text on top of that.
 *
 * Solve two DIFFERENT navigation gestures differently:
 * - root from Home: the new settings sheet arrives from the right/top; Home
 *   is stationary below it. Only the settings sheet scales very subtly.
 * - child within settings: a conventional coordinated edge-to-edge PUSH:
 *   old page exits left exactly as new page enters right. Neither scales,
 *   fades nor reflows. Back runs the matched reverse push. The paired pages
 *   remain neighbours instead of the old page holding still while the
 *   incoming page overlaps it.
 *
 * All springs in a pair use identical physical constants and move the SAME
 * travel distance. No double animation inside CapsuleRouteSurface; no global
 * route transitions outside settings (artwork/lifecycle regression guard).
 */
private fun String?.isSettingsPage(): Boolean =
    this == "settings" || this?.startsWith("settings/") == true

private val Corner = TransformOrigin(1f, 0.10f)

// Fast but gently settled. A single coordinate system for both pages.
// Damping ~1 removes the secondary "knock" from multiple overshooting pages.
private val NestedPush = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 400f,
)
private val RootEnterPosition = spring<IntOffset>(
    dampingRatio = 0.92f,
    stiffness = 330f,
)
private val RootEnterScale = spring<Float>(
    dampingRatio = 0.92f,
    stiffness = 330f,
)
private val RootExitPosition = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 430f,
)
private val RootExitScale = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 430f,
)

internal data class SettingsPageTravel(
    val startOffsetFraction: Float,
    val startScale: Float,
)

private val RootTravel = SettingsPageTravel(startOffsetFraction = 0.88f, startScale = 0.86f)
private val DetailTravel = SettingsPageTravel(startOffsetFraction = 1f, startScale = 1f)

internal fun settingsPageTravel(from: String?): SettingsPageTravel =
    if (from.isSettingsPage()) DetailTravel else RootTravel

/** Pure routing policy makes accidental animated non-settings pages testable. */
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
                if (isPop) {
                    slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = NestedPush,
                    )
                } else {
                    slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = NestedPush,
                    )
                }
            }
            SettingsRouteMotion.ROOT -> {
                if (isPop || !to.isSettingsPage()) EnterTransition.None
                else slideInHorizontally(
                    initialOffsetX = { (it * RootTravel.startOffsetFraction).toInt() },
                    animationSpec = RootEnterPosition,
                ) + scaleIn(
                    initialScale = RootTravel.startScale,
                    transformOrigin = Corner,
                    animationSpec = RootEnterScale,
                )
            }
        }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        when (settingsRouteMotion(from, to)) {
            SettingsRouteMotion.NONE -> ExitTransition.None
            SettingsRouteMotion.NESTED -> {
                if (isPop) {
                    slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = NestedPush,
                    )
                } else {
                    slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = NestedPush,
                    )
                }
            }
            SettingsRouteMotion.ROOT -> {
                if (!isPop || !from.isSettingsPage()) ExitTransition.None
                else slideOutHorizontally(
                    targetOffsetX = { (it * RootTravel.startOffsetFraction).toInt() },
                    animationSpec = RootExitPosition,
                ) + scaleOut(
                    targetScale = RootTravel.startScale,
                    transformOrigin = Corner,
                    animationSpec = RootExitScale,
                )
            }
        }
}
