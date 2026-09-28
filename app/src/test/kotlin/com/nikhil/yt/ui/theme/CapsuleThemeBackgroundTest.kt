package com.nikhil.yt.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleThemeBackgroundTest {
    @Test
    fun `capsule dark background uses exact requested neutral`() {
        val scheme = darkColorScheme().capsule(pureBlack = false)

        assertEquals(0xFF141414.toInt(), scheme.background.toArgb())
        assertEquals(0xFF141414.toInt(), scheme.surface.toArgb())
    }
}
