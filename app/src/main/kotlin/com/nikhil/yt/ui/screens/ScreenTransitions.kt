package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Capsule Settings V5, based on the actual 9481.mp4 recording.
 *
 * 00-08 s: old scaleOut(0.68) ended with a 68%-size Settings screen still
 * visible in the middle of Home, then Navigation removed it in one frame.
 * This is not a spring problem: the destination never physically left.
 * Root now travels COMPLETELY off the viewport on close, with a very late
 * fade. Its arrival comes from upper-right with a shallow (4%) scale.
 *
 * 12-25 s: two slow opaque settings pages passed each other for 340ms. Text
 * from the leaving page stayed readable under the entering page, producing
 * the "old UI debris" effect. Nested travel now completes in 215ms, and
 * the outgoing old page fades promptly. No child animation or text scaling.
 *
 * Every geometry component of a paired motion uses the SAME duration and
 * easing. No time-unbounded spring on a full-screen NavHost destination.
 * Only settings routes animate; heavy player/artist routes stay instant.
 */
private fun String?.isSettingsPage(): Boolean =
    this == "settings" || this?.startsWith("settings/") == true

private val topRight = TransformOrigin(1f, 0f)
private const val RootEnterMs = 300
private const val RootExitMs = 270
private const val NestedMs = 215
private val rootEase = CubicBezierEasing(0.22f, 0.0f, 0.70f, 1f)
private val closeEase = CubicBezierEasing(0.30f, 0f, 0.76f, 1f)
private val nestedEase = CubicBezierEasing(0.24f, 0f, 0.76f, 1f)

// Small visual perspective at origin, never an exaggerated shrinking page.
internal const val SettingsRootInitialScale = 0.96f
internal data class SettingsPageTravel(
    val startOffsetFraction: Float,
    val startScale: Float,
)
private val RootTravel = SettingsPageTravel(startOffsetFraction = 0.46f, startScale = SettingsRootInitialScale)
private val DetailTravel = SettingsPageTravel(startOffsetFraction = 1f, startScale = 1f)
internal fun settingsPageTravel(from: String?): SettingsPageTravel =
    if (from.isSettingsPage()) DetailTravel else RootTravel

internal fun settingsRootInitialOffset(size: IntSize): IntOffset =
    IntOffset((size.width * RootTravel.startOffsetFraction).toInt(), -(size.height * 0.035f).toInt())

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
            SettingsRouteMotion.NESTED ->
                slideInHorizontally(
                    initialOffsetX = { if (isPop) -it else it },
                    animationSpec = tween(NestedMs, easing = nestedEase),
                ) + fadeIn(
                    // Incoming UI becomes legible early; outgoing UI has
                    // already begun fading, so letters never crowd together.
                    animationSpec = tween(100),
                    initialAlpha = 0.30f,
                )
            SettingsRouteMotion.ROOT ->
                if (isPop || !to.isSettingsPage()) EnterTransition.None
                else slideIn(
                    initialOffset = ::settingsRootInitialOffset,
                    animationSpec = tween(RootEnterMs, easing = rootEase),
                ) + scaleIn(
                    initialScale = SettingsRootInitialScale,
                    transformOrigin = topRight,
                    animationSpec = tween(RootEnterMs, easing = rootEase),
                ) + fadeIn(
                    initialAlpha = 0.8f,
                    animationSpec = tween(100),
                )
        }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        when (settingsRouteMotion(from, to)) {
            SettingsRouteMotion.NONE -> ExitTransition.None
            SettingsRouteMotion.NESTED ->
                slideOutHorizontally(
                    targetOffsetX = { if (isPop) it else -it },
                    animationSpec = tween(NestedMs, easing = nestedEase),
                ) + fadeOut(
                    targetAlpha = 0f,
                    animationSpec = tween(120, easing = nestedEase),
                )
            SettingsRouteMotion.ROOT ->
                if (!isPop || !from.isSettingsPage()) ExitTransition.None
                else slideOutHorizontally(
                    // The old 0.68 scale endpoint was the exact 00:01
                    // freeze-and-vanish in the user's recording. Travel
                    // the ENTIRE width; no page fragment is left behind.
                    targetOffsetX = { it },
                    animationSpec = tween(RootExitMs, easing = closeEase),
                ) + scaleOut(
                    targetScale = 0.975f,
                    transformOrigin = topRight,
                    animationSpec = tween(RootExitMs, easing = closeEase),
                ) + fadeOut(
                    // Keep the departing panel readable for the first
                    // two thirds, dissolve ONLY once almost fully outside.
                    animationSpec = keyframes {
                        durationMillis = RootExitMs
                        1f at 0
                        1f at 190
                        0f at RootExitMs
                    },
                )
        }
}
