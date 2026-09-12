package com.nikhil.yt.ui.component

import androidx.compose.ui.graphics.Color

/** Single accent for the active/filled like state across Capsule UI. */
object CapsuleFavoriteColors {
    val active = Color(0xFF822133)

    @Suppress("UNUSED_PARAMETER")
    fun selected(contentColor: Color): Color = active
}
