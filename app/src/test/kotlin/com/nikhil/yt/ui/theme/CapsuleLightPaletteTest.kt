package com.nikhil.yt.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleLightPaletteTest {
    private fun contrast(foreground: Color, background: Color): Float {
        val hi = maxOf(foreground.luminance(), background.luminance())
        val lo = minOf(foreground.luminance(), background.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test fun lightSurfaceHierarchyAndTextRemainLegible() {
        val scheme = lightColorScheme().capsuleLight()
        assertTrue(scheme.background != scheme.surfaceContainer)
        assertTrue(scheme.surfaceContainer != scheme.surfaceContainerHigh)
        assertTrue(contrast(scheme.onBackground, scheme.background) >= 7f)
        assertTrue(contrast(scheme.onSurfaceVariant, scheme.surface) >= 4.5f)
        assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 7f)
    }

    @Test fun applyingLightSchemeCannotMutateDarkCapsuleValues() {
        val darkBase = darkColorScheme()
        val before = darkBase.capsule(pureBlack = false)
        lightColorScheme().capsuleLight()
        val after = darkBase.capsule(pureBlack = false)
        assertEquals(before.background, after.background)
        assertEquals(before.onSurface, after.onSurface)
        assertEquals(Color(0xFF141414), after.surface)
    }
}
