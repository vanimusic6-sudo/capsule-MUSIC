package com.nikhil.yt.ui

import com.nikhil.yt.ui.motion.CapsuleEnterEasing
import com.nikhil.yt.ui.motion.CapsuleShortestVisible
import com.nikhil.yt.ui.screens.DestinationMotion
import com.nikhil.yt.ui.screens.RouteDirection
import com.nikhil.yt.ui.screens.Screens
import com.nikhil.yt.ui.screens.destinationMotionFor
import com.nikhil.yt.ui.screens.routeDirection
import com.nikhil.yt.ui.screens.spec
import org.junit.Assert.assertEquals
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

    /**
     * Detail screens arrive with a fade and nothing else.
     *
     * A transform is what tore here: the entrance layer sits inside the destination's own opaque
     * canvas, so moving or scaling it uncovers a band of surface colour along an edge that is
     * full-bleed artwork. Leaving these screens with no entrance at all is not the answer either —
     * arriving on an artwork page in a single frame is the jarring cut this motion system exists to
     * remove.
     */
    @Test
    fun artworkHeavyDetailScreensFadeAndDoNotMove() {
        listOf("artist/abc", "album/xyz", "search/q", null).forEach { route ->
            assertEquals(DestinationMotion.Detail, destinationMotionFor(route))
        }

        val detail = DestinationMotion.Detail.spec()
        assertTrue("detail screens must have an entrance", detail.fade > 0f)
        assertEquals("a moved detail screen comes apart at its edge", 0f, detail.lift.value, 0f)
        assertEquals(0f, detail.shift.value, 0f)
        assertEquals(0f, detail.overscale, 0f)
        assertTrue(
            "starting from nothing reads as a flash, not an arrival",
            detail.fade <= 0.5f,
        )
        assertTrue("a detail entrance must stay brief", detail.durationMillis <= 320)
    }

    /** The fade is for artwork pages only; everything else moves instead. */
    @Test
    fun onlyDetailScreensFade() {
        DestinationMotion.entries.forEach { motion ->
            if (motion == DestinationMotion.Detail) return@forEach
            assertEquals("$motion must not fade", 0f, motion.spec().fade, 0f)
        }
    }

    @Test
    fun settingsPagesMoveSidewaysWithoutScaling() {
        val settings = DestinationMotion.Settings.spec()
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

        val section = DestinationMotion.Section.spec()
        assertTrue(section.overscale > 0f)
        assertEquals(0f, section.lift.value, 0f)
        assertEquals(0f, section.shift.value, 0f)
    }

    @Test
    fun everyEntranceDoesSomethingAndStaysShort() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec()
            assertTrue(
                "$motion animates nothing at all",
                spec.lift.value > 0f ||
                    spec.shift.value > 0f ||
                    spec.overscale > 0f ||
                    spec.fade > 0f,
            )
            assertTrue(
                "$motion takes ${spec.durationMillis}ms",
                spec.durationMillis in CapsuleShortestVisible..620,
            )
            assertTrue(spec.lift.value <= 32f)
            assertTrue(spec.shift.value <= 40f)
            assertTrue(spec.overscale <= 0.06f)
            assertTrue(spec.fade <= 0.5f)
        }
    }

    /**
     * Every entrance runs on the app's measured curve, not on one of its own.
     *
     * Four hand-tuned beziers used to live here and each was pinned by a threshold of its own, which
     * meant the tests described those four curves rather than what makes an entrance feel right.
     * The properties that matter -- starts at rest, arrives at rest, never whips through the middle
     * -- are held once, in CapsuleEasingTest, for the curve they all now share.
     */
    @Test
    fun everyEntranceUsesTheSharedArrivalCurve() {
        DestinationMotion.entries.forEach { motion ->
            assertEquals(
                "$motion brought its own curve instead of the app's",
                CapsuleEnterEasing,
                motion.spec().easing,
            )
        }
    }

    @Test
    fun onlySectionMotionScales() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec()
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
