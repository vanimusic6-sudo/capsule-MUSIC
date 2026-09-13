package com.nikhil.yt.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

/**
 * Route navigation never visibly moves a whole page.
 *
 * Navigation Compose still needs a real, finite transition so the outgoing destination can leave
 * STARTED and be disposed. Returning EnterTransition.None/ExitTransition.None left both route
 * contents alive together on some fast changes, which made translucent destinations visibly stack.
 * A 1 ms identity-scale transition has no visible motion, alpha or slide, but gives NavHost a clean
 * transition boundary. Individual destinations remain responsible for their own element motion.
 */
internal object ScreenTransitions {
    @Suppress("UNUSED_PARAMETER")
    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition =
        scaleIn(
            initialScale = 1f,
            animationSpec = tween(durationMillis = 1),
        )

    @Suppress("UNUSED_PARAMETER")
    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition =
        scaleOut(
            targetScale = 1f,
            animationSpec = tween(durationMillis = 1),
        )
}
