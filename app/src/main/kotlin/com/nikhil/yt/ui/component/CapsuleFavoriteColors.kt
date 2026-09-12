package com.nikhil.yt.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** The full player's neutral filled heart, with the same contrast rule on every surface. */
object CapsuleFavoriteColors {
    val onDark = Color.White
    val onLight = Color(0xFF171717)

    fun selected(contentColor: Color): Color =
        if (contentColor.luminance() >= 0.5f) onDark else onLight
}
