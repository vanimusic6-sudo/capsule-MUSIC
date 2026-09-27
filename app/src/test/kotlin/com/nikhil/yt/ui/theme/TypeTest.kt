package com.nikhil.yt.ui.theme

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class TypeTest {
    @Test
    fun appTypographyUsesScriptSafeGenericSansFamily() {
        val roles =
            listOf(
                AppTypography.displayLarge,
                AppTypography.headlineMedium,
                AppTypography.titleLarge,
                AppTypography.bodyLarge,
                AppTypography.bodyMedium,
                AppTypography.labelLarge,
            )

        roles.forEach { style ->
            assertEquals(FontFamily.SansSerif, style.fontFamily)
        }
    }

    @Test
    fun systemTypographyStillUsesDeviceDefaultFamily() {
        assertEquals(FontFamily.Default, SystemTypography.bodyLarge.fontFamily)
        assertEquals(FontFamily.Default, SystemTypography.titleLarge.fontFamily)
    }
}
