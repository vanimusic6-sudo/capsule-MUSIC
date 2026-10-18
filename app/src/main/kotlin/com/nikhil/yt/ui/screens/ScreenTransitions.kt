package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * Capsule Settings: ArchiveTune-inspired route motion.
 *
 * ArchiveTune uses a half-viewport horizontal push plus a coordinated
 * cross-fade for its non-tab routes (including Settings). Unlike our
 * previous "shrink to 68% then disappear" and later "slide a full
 * viewport offscreen", the old page is fully invisible by the END of
 * a bounded short transition. No freeze or one-frame final removal.
 *
 * Local refinement: shared easing, softer opacity overlap, and a slightly
 * shorter return. Scope ONLY to Settings to protect heavy player/video
 * backstack lifecycles from the previous global transition regression.
 *
 * Enter / exit both participate for home <-> settings. Never apply a
 * second transform in CapsuleRouteSurface. No full-screen scale at all.
 */
private fun String?.isSettingsPage(): Boolean =
    this == "settings" || this?.startsWith("settings/") == true

private val NavigationEase = CubicBezierEasing(0.24f, 0.02f, 0.72f, 1f)
private val ExitEase = CubicBezierEasing(0.32f, 0f, 0.75f, 1f)

private const val ROOT_ENTER_MS = 255
private const val ROOT_EXIT_MS = 205
private const val NESTED_ENTER_MS = 215
private const val NESTED_EXIT_MS = 170

// Retain the public helper for existing structural/unit tests and change
// its meaning to truthful full-size destinations (no root shrinking).
internal const val SettingsRootInitialScale = 1f
internal data class SettingsPageTravel(
    val startOffsetFraction: Float,
    val startScale: Float,
)
private val RootTravel = SettingsPageTravel(0.5f, 1f)
private val DetailTravel = SettingsPageTravel(1f, 1f)
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
            SettingsRouteMotion.ROOT -> {
                // Open Settings: arrives from half-right; reveal Home on Back:
                // Home returns from half-left, like ArchiveTune's pop enter.
                val direction = if (isPop) -1 else 1
                slideInHorizontally(
                    initialOffsetX = { width -> direction * width / 2 },
                    animationSpec = tween(ROOT_ENTER_MS, easing = NavigationEase),
                ) + fadeIn(
                    initialAlpha = 0.02f,
                    animationSpec = tween(ROOT_ENTER_MS, easing = NavigationEase),
                )
            }
            SettingsRouteMotion.NESTED -> slideInHorizontally(
                initialOffsetX = { width -> if (isPop) -width else width },
                animationSpec = tween(NESTED_ENTER_MS, easing = NavigationEase),
            ) + fadeIn(
                initialAlpha = 0.30f,
                animationSpec = tween(90, easing = NavigationEase),
            )
        }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        when (settingsRouteMotion(from, to)) {
            SettingsRouteMotion.NONE -> ExitTransition.None
            SettingsRouteMotion.ROOT -> {
                // Settings->Home: Settings goes HALF right and fades out
                // continuously. Forward: Home goes half left and fades out.
                // At disposal alpha is already zero. No "halfway then pop".
                slideOutHorizontally(
                    targetOffsetX = { width -> if (isPop) width / 2 else -width / 2 },
                    animationSpec = tween(ROOT_EXIT_MS, easing = ExitEase),
                ) + fadeOut(
                    targetAlpha = 0f,
                    animationSpec = tween(ROOT_EXIT_MS, easing = ExitEase),
                )
            }
            SettingsRouteMotion.NESTED -> slideOutHorizontally(
                targetOffsetX = { width -> if (isPop) width else -width },
                animationSpec = tween(NESTED_EXIT_MS, easing = ExitEase),
            ) + fadeOut(
                targetAlpha = 0f,
                animationSpec = tween(120, easing = ExitEase),
            )
        }
}
