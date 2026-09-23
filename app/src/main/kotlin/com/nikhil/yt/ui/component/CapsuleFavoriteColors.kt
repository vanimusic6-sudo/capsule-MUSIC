package com.nikhil.yt.ui.component

import androidx.compose.ui.graphics.Color

/** Shared Capsule heart colors. The icon itself owns the active burgundy state. */
object CapsuleFavoriteColors {
    val active = Color(0xFF822133)

    /**
     * Neutral contour used before a like is applied.
     *
     * Kept behind the existing helper so both Capsule Light and Dance resolve the exact same
     * inactive tone without duplicating color rules in each player layout.
     */
    fun selected(contentColor: Color): Color = contentColor.copy(alpha = 0.72f)
}
