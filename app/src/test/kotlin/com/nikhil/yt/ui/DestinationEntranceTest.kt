package com.nikhil.yt.ui

import com.nikhil.yt.ui.screens.DestinationMotion
import com.nikhil.yt.ui.screens.RouteDirection
import com.nikhil.yt.ui.screens.Screens
import com.nikhil.yt.ui.screens.destinationMotionFor
import com.nikhil.yt.ui.screens.routeDirection
import com.nikhil.yt.ui.screens.spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationEntranceTest {
    @Test
    fun bottomBarDestinationsUseTabMotion() {
        Screens.MainScreens.forEach { screen ->
            assertEquals(
                "${screen.route} should move like a tab",
                DestinationMotion.Tab,
                destinationMotionFor(screen.route),
            )
        }
    }

    @Test
    fun artworkHeavyDetailScreensDoNotCreateAnAnimatedLayer() {
        listOf("artist/abc", "album/xyz", "search/q", null).forEach { route ->
            assertEquals(DestinationMotion.Detail, destinationMotionFor(route))
        }
        assertNull(
            "detail screens must not fall back to fade/alpha or a tearing full-screen transform",
            DestinationMotion.Detail.spec(),
        )
    }

    @Test
    fun settingsPagesMoveSidewaysWithoutScaling() {
        val settings = requireNotNull(DestinationMotion.Settings.spec())
        assertTrue(settings.shift.value > 0f)
        assertEquals(0f, settings.lift.value, 0f)
        assertEquals(0f, settings.overscale, 0f)
        assertTrue(settings.backwardDurationMillis < settings.durationMillis)
    }

    @Test
    fun crossingSettingsBoundaryUsesTheSectionMotion() {
        listOf(
            "settings" to "home",
            "settings/appearance" to "library",
            "home" to "settings",
            "library" to "settings/appearance",
        ).forEach { (route, from) ->
            assertEquals(
                "$from -> $route crosses the settings boundary",
                DestinationMotion.Section,
                destinationMotionFor(route, from),
            )
        }

        val section = requireNotNull(DestinationMotion.Section.spec())
        assertTrue(section.overscale > 0f)
        assertEquals(0f, section.lift.value, 0f)
        assertEquals(0f, section.shift.value, 0f)
    }

    @Test
    fun everyAnimatedEntranceIsGeometryOnlyAndShort() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec() ?: return@forEach
            assertTrue(
                "$motion has no visible geometry",
                spec.lift.value > 0f || spec.shift.value > 0f || spec.overscale > 0f,
            )
            assertTrue("$motion takes ${spec.durationMillis}ms", spec.durationMillis in 250..520)
            assertTrue(spec.lift.value <= 32f)
            assertTrue(spec.shift.value <= 40f)
            assertTrue(spec.overscale <= 0.06f)
        }
    }

    @Test
    fun tabCurveKeepsAVisibleSoftTail() {
        val tab = requireNotNull(DestinationMotion.Tab.spec())
        val remaining = 1f - tab.easing.transform(2f / 3f)
        assertTrue(
            "only ${remaining * 100}% of travel remains for the final third",
            remaining >= 0.08f,
        )
    }

    @Test
    fun settingsCurveDoesNotJumpOffTheLine() {
        val settings = requireNotNull(DestinationMotion.Settings.spec())
        val startedBy = settings.easing.transform(0.1f)
        assertTrue("a tenth in, $startedBy of travel is already spent", startedBy < 0.04f)
    }

    @Test
    fun onlySectionMotionScales() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec() ?: return@forEach
            if (motion == DestinationMotion.Section) {
                assertTrue(spec.overscale > 0f)
            } else {
                assertEquals("$motion must not scale", 0f, spec.overscale, 0f)
            }
        }
    }

    @Test
    fun routeDirectionIsBackwardOnlyForAnAncestor() {
        assertEquals(RouteDirection.Backward, routeDirection("settings/appearance", "settings"))
        assertEquals(
            RouteDirection.Backward,
            routeDirection("settings/appearance/palette", "settings/appearance"),
        )
        assertEquals(RouteDirection.Forward, routeDirection("settings/audio", "settings/appearance"))
        assertEquals(RouteDirection.Forward, routeDirection("settings_backup", "settings"))
        assertEquals(RouteDirection.Forward, routeDirection(null, "home"))
    }
}
