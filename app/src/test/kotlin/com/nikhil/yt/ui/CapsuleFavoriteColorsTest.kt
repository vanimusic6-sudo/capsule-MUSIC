package com.nikhil.yt.ui

import androidx.compose.ui.graphics.Color
import com.nikhil.yt.ui.component.CapsuleFavoriteColors
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleFavoriteColorsTest {
    @Test
    fun inactiveHeartUsesSharedForegroundTone() {
        for (foreground in listOf(Color.White, Color(0xFFF4F4F4), Color.Black, Color(0xFF171717))) {
            assertEquals(foreground.copy(alpha = 0.72f), CapsuleFavoriteColors.selected(foreground))
        }
    }

    @Test
    fun activeHeartKeepsCapsuleBurgundyAccent() {
        assertEquals(Color(0xFF822133), CapsuleFavoriteColors.active)
    }
}
