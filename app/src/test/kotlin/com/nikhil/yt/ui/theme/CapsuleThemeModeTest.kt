package com.nikhil.yt.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/**
 * Pure theme-state regressions. Compose's existing animated scheme is intentionally
 * not modified by this fix. Device screenshots still need manual verification.
 */
class CapsuleThemeModeTest {
    private val light = lightColorScheme(
        primary = Color(0xFF456789),
        background = Color(0xFFFDFCF9),
        surface = Color(0xFFF8F6F3),
    )

    @Test fun capsuleEnabledInExplicitLightModeDoesNotForceDarkColors() {
        val actual = resolveCapsuleColorScheme(
            baseColorScheme = light,
            capsuleThemeEnabled = true,
            isDark = false,
            pureBlack = true, // stored preference must be ignored in light mode
        )
        assertEquals(Color(0xFFF7F7F4), actual.background)
        assertEquals(Color(0xFF222529), actual.onBackground)
        assertEquals(Color(0xFFEAEBE7), actual.surfaceContainer)
        assertEquals(Color(0xFF585F64), actual.onSurfaceVariant)
        assertEquals(light.capsuleLight().primary, actual.primary)
    }

    @Test fun capsuleDarkModeUsesTheUnmodifiedExistingDarkPalette() {
        val base = darkColorScheme()
        val actual = resolveCapsuleColorScheme(
            baseColorScheme = base, capsuleThemeEnabled = true, isDark = true, pureBlack = false,
        )
        assertEquals(Color(0xFF141414), actual.surface)
        assertEquals(Color(0xFF141414), actual.background)
        // Material3 ColorScheme does not implement structural equals().
        // Compare actual semantic roles rather than two equivalent instances.
        val expected = base.capsule(false)
        assertEquals(expected.primary, actual.primary)
        assertEquals(expected.onBackground, actual.onBackground)
        assertEquals(expected.surfaceContainer, actual.surfaceContainer)
        assertEquals(expected.outline, actual.outline)
    }

    @Test fun capsuleDarkPureBlackRemainsSupportedWithoutChangingOtherColors() {
        val base = darkColorScheme()
        val regular = resolveCapsuleColorScheme(base, capsuleThemeEnabled = true, isDark = true, pureBlack = false)
        val black = resolveCapsuleColorScheme(base, capsuleThemeEnabled = true, isDark = true, pureBlack = true)
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)
        assertEquals(regular.primary, black.primary)
        assertEquals(regular.secondary, black.secondary)
    }

    @Test fun standardThemeAndAutomaticLightDoNotGainCapsuleOverride() {
        val baseDark = darkColorScheme()
        assertEquals(light.capsuleLight().background, resolveCapsuleColorScheme(light, true, false, false).background)
        assertSame(light, resolveCapsuleColorScheme(light, false, false, false))
        assertSame(baseDark, resolveCapsuleColorScheme(baseDark, false, true, false))
        assertEquals(Color.Black, resolveCapsuleColorScheme(baseDark, false, true, true).background)
    }

    @Test fun rapidModeChangesAlwaysResolveFromCurrentModeWithoutStaleState() {
        val initial = resolveCapsuleColorScheme(light, capsuleThemeEnabled = true, isDark = false, pureBlack = false)
        val dark = resolveCapsuleColorScheme(darkColorScheme(), capsuleThemeEnabled = true, isDark = true, pureBlack = false)
        val finalLight = resolveCapsuleColorScheme(light, capsuleThemeEnabled = true, isDark = false, pureBlack = false)
        assertEquals(Color(0xFFF7F7F4), initial.background)
        assertEquals(Color(0xFF141414), dark.background)
        assertEquals(Color(0xFFF7F7F4), finalLight.background)
    }

    @Test fun activityPassesStoredExplicitOrSystemModeToRootTheme() {
        val root = listOf(
            File("src/main/kotlin/com/nikhil/yt/MainActivity.kt"),
            File("app/src/main/kotlin/com/nikhil/yt/MainActivity.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("Missing MainActivity")
        assertEquals(true, root.contains("if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON"))
        assertEquals(true, root.contains("darkTheme = useDarkTheme,"))
        assertEquals(true, root.contains("LaunchedEffect(playerConnection, enableDynamicTheme, customThemeColor)"))
    }
}
