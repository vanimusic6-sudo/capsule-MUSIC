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
import com.nikhil.yt.ui.motion.CapsuleExitEasing

/**
 * A single, deliberately scoped exception to instant route changes.
 *
 * Settings is a physical sheet of pages. It approaches from the right/top
 * (where the Home settings action lives), grows into the viewport and returns
 * to the right to uncover its ACTUAL previous screen on Back.
 *
 * All tab, player, artwork and other routes retain None transitions. In
 * particular, do not keep two tabs composed: that caused a destroyed
 * NavBackStackEntry/ViewModel race and hitches during repeated tab taps.
 *
 * No fades (a translucent destination exposes an artificial grey canvas),
 * no blur, no perpetual clocks and no animations after the NavHost settles.
 */
private fun String?.isSettingsPage(): Boolean =
    this == "settings" || this?.startsWith("settings/") == true

private val SettingsOrigin = TransformOrigin(1f, 0.08f)
private val SettingsEnterSpec = spring<IntOffset>(
    dampingRatio = 0.90f,
    stiffness = Spring.StiffnessMediumLow,
)
private val SettingsGrowSpec = spring<Float>(
    dampingRatio = 0.90f,
    stiffness = Spring.StiffnessMediumLow,
)

object ScreenTransitions {
    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        if (!isPop && to.isSettingsPage()) {
            slideInHorizontally(
                initialOffsetX = { (it * 0.36f).toInt() },
                animationSpec = SettingsEnterSpec,
            ) + scaleIn(
                initialScale = 0.92f,
                transformOrigin = SettingsOrigin,
                animationSpec = SettingsGrowSpec,
            )
        } else {
            EnterTransition.None
        }

    @Suppress("UNUSED_PARAMETER")
    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        if (isPop && from.isSettingsPage()) {
            slideOutHorizontally(
                targetOffsetX = { (it * 0.36f).toInt() },
                animationSpec = tween(durationMillis = 390, easing = CapsuleExitEasing),
            ) + scaleOut(
                targetScale = 0.92f,
                transformOrigin = SettingsOrigin,
                animationSpec = tween(durationMillis = 390, easing = CapsuleExitEasing),
            )
        } else {
            ExitTransition.None
        }
}
