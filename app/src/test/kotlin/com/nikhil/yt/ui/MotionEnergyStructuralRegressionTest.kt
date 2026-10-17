package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guards for the motion ownership/energy regressions. These are intentionally
 * structural; they complement but do not replace real frame-timing and thermal device tests.
 */
class MotionEnergyStructuralRegressionTest {
    private fun source(relativePath: String): String {
        val relative = "com/nikhil/yt/ui/$relativePath"
        return listOf(
            File("src/main/kotlin/$relative"),
            File("app/src/main/kotlin/$relative"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing source file: $relative")
    }

    @Test
    fun navigationIndicatorSamplesTheSpringDuringPlacementNotComposition() {
        val content = source("component/FluidSlidingNavigationBar.kt")
        assertTrue(content.contains("val indicatorOffset = animateDpAsState("))
        assertTrue(content.contains("x = indicatorOffset.value.roundToPx()"))
        assertTrue(content.contains(".offset {"))
        assertFalse(content.contains("val indicatorOffset by animateDpAsState("))
        assertFalse(content.contains("Modifier.offset(x = indicatorOffset,"))
    }

    @Test
    fun settingsModeReorderReusesSpecsAndCannotBounce() {
        val content = source("screens/settings/VeluneSettingsScreen.kt")
        assertEquals(14, content.split(".animateItem(").size - 1)
        assertEquals(14, content.split("placementSpec = SettingsRowPlacement").size - 1)
        assertEquals(1, content.split("private val SettingsRowPlacement = spring<IntOffset>(").size - 1)
        assertTrue(content.contains("dampingRatio = Spring.DampingRatioNoBouncy"))
        assertEquals(14, content.split("fadeInSpec = SettingsRowFadeIn").size - 1)
        assertEquals(14, content.split("fadeOutSpec = SettingsRowFadeOut").size - 1)
    }

    @Test
    fun animatedBottomSheetRetainsGestureShieldUntilItIsGone() {
        val content = source("component/BottomSheetPage.kt")
        assertTrue(content.contains("MutableTransitionState(false)"))
        assertTrue(content.contains("visibility.targetState = state.isVisible"))
        assertTrue(content.contains("visibility.currentState || visibility.targetState"))
        assertTrue(content.contains("visibleState = visibility"))
        assertTrue(content.contains("BackHandler(enabled = state.isVisible)"))
        assertFalse(content.contains("visible = state.isVisible,"))
    }
}
