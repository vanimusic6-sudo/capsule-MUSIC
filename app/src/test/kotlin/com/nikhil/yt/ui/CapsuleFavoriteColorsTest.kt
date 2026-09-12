package com.nikhil.yt.ui

import androidx.compose.ui.graphics.Color
import com.nikhil.yt.ui.component.CapsuleFavoriteColors
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleFavoriteColorsTest {
    @Test fun everyDarkSurfaceUsesTheSharedBurgundyHeart() {
        for (foreground in listOf(Color.White, Color(0xFFF4F4F4), Color(0xFFF3F3F3), Color(0xFFDDDDDD))) {
            assertEquals(Color(0xFF822133), CapsuleFavoriteColors.selected(foreground))
        }
    }

    @Test fun lightSurfacesKeepTheSameBurgundyAccent() {
        for (foreground in listOf(Color.Black, Color(0xFF171717), Color(0xFF444444))) {
            assertEquals(Color(0xFF822133), CapsuleFavoriteColors.selected(foreground))
        }
    }
}
