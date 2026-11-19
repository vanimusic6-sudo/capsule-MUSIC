package com.nikhil.yt.ui.player

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CapsuleInlineLyricsDemandAndPaletteTest {
    @Test fun idleAndTinyJitterDoNotStartInlineLyricsNetwork() {
        assertFalse(capsuleShouldPrefetchInlineLyrics(0, 1000f))
        assertFalse(capsuleShouldPrefetchInlineLyrics(20, 1000f))
        assertTrue(capsuleShouldPrefetchInlineLyrics(30, 1000f))
        assertFalse(capsuleShouldPrefetchInlineLyrics(60, Float.NaN))
    }

    @Test fun immersionSamplesActualVisiblePhotoFieldInsteadOfNeutralParent() {
        val parent = listOf(Color(0xFF1A1B21), Color(0xFF1A1B21), Color(0xFF1A1B21))
        val field = CapsuleColorField(
            width = 2, height = 2,
            cells = listOf(
                Color(0xFF442268), Color(0xFF442268),
                Color(0xFF7734B4), Color(0xFF7734B4),
            ),
        )
        val tone = ImmersiveArtworkTone(
            edge = Color(0xFF592E85),
            accent = Color(0xFF251436),
            colorField = field,
            ready = true,
        )
        val derived = capsuleInlineLyricsSceneColors(parent, tone)
        assertEquals(3, derived.size)
        assertTrue("Immersive violet must survive into the below-player card",
            derived[0].blue > derived[0].green * 1.30f)
        assertTrue("Card must not reuse default neutral album palette", derived != parent)
        assertEquals("Unprepared tone must be ignored", parent,
            capsuleInlineLyricsSceneColors(parent, tone.copy(ready = false)))
    }

    @Test fun compactAndFullScreenStopsStayConsistentAndHued() {
        val sample = listOf(
            Color(0xFF6F298C), Color(0xFF441C68), Color(0xFF261334),
        )
        val stops = capsuleInlineLyricsStops(sample)
        assertEquals(3, stops.size)
        assertTrue(stops[0].blue > stops[0].green)
        assertTrue(stops[1].blue > stops[1].green)
        assertTrue(stops[0].blue > stops[2].blue)
    }

    @Test fun markersStayPermanentAndMagneticallyCaptureNearbyDrags() {
        val width = 220f
        val first = 10f
        val tick = 2.7f
        val stride = 11f
        val mark = first + tick / 2f + 5 * stride
        val raw = (mark + 2.7f) / width
        val dragged = capsuleLyricsMagneticVolume(raw, width, first, tick, stride, 7f, false)
        val settled = capsuleLyricsMagneticVolume(raw, width, first, tick, stride, 7f, true)
        assertTrue(abs(dragged - mark / width) < abs(raw - mark / width))
        assertEquals(mark / width, settled, 0.0001f)
        assertEquals(0f, capsuleLyricsMagneticVolume(0f, width, first, tick, stride, 7f, true), 0.0001f)
        assertEquals(1f, capsuleLyricsMagneticVolume(1f, width, first, tick, stride, 7f, true), 0.0001f)
    }
}
