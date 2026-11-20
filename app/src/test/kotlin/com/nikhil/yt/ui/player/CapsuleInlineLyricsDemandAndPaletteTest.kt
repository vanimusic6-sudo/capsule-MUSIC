package com.nikhil.yt.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
        // The bug used a pre-darkened accent (35% of the visible artwork)
        // which then got dimmed AGAIN, leaving a gray empty rectangle.
        assertTrue("Bottom of card must preserve visible artwork's violet",
            derived[2].blue > tone.accent.blue * 1.7f)
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

    @Test fun immersionLyricsKeepTheSameLeftToRightPhotoColoursAcrossTheScrollGap() {
        val left = Color(0xFFA74C23)
        val center = Color(0xFF22222B)
        val right = Color(0xFF673DA8)
        val field = CapsuleColorField(
            width = 3, height = 2,
            cells = listOf(left, center, right, left, center, right),
        )
        val tone = ImmersiveArtworkTone(
            colorField = field,
            ready = true,
        )
        val sampled = capsuleInlineLyricsSceneColors(
            listOf(Color.Black, Color.Black, Color.Black), tone,
        )
        assertEquals(listOf(left, center, right), sampled)
        val stops = capsuleInlineLyricsImmersiveStops(sampled)
        assertTrue(stops[0].red > stops[1].red)
        assertTrue(stops[2].blue > stops[1].blue)
        assertTrue("The same three shared stops preserve two-dimensional Immersion",
            stops.toSet().size == 3)
    }

    @Test fun immersionPanelIsDistinctButDerivedFromTheSharedSeam() {
        val photo = listOf(Color(0xFF68391E), Color(0xFF583A26), Color(0xFF2D211E))
        val card = capsuleInlineLyricsImmersivePanelStops(photo)
        assertEquals(2, card.size)
        assertTrue("The card must tint the mirrored photo rather than replace it",
            card.all { it.alpha in 0.7f..0.99f })
        assertTrue("Small lyric card must be dimmer than its live photo reference",
            card.first().red < photo[1].red)
        assertTrue("The dimmer card still retains recognizable artwork hue",
            card.first().red > 0.12f)
        assertTrue("A warm image must remain warm inside its own card",
            card.first().red > card.first().blue * 1.12f)
    }

    @Test fun canvasLyricsContinueActualVideoFieldInsteadOfAlbumColours() {
        val canvasField = CapsuleColorField(20, 16, List(320) { i ->
            if (i % 20 < 10) Color(0xFFF33E42) else Color(0xFF2D51F8)
        })
        val seam = capsuleCanvasLyricsSeamColors(canvasField)
        assertEquals(3, seam.size)
        assertTrue(seam[0].red > seam[0].blue)
        assertTrue(seam[2].blue > seam[2].red)
        assertTrue("Native Canvas lower edge must be dark, not a neon lyric stripe",
            seam.all { it.red < 0.30f && it.green < 0.30f && it.blue < 0.30f })
    }

    @Test fun extremelyBrightRedAndPinkCoversAreSubduedOnlyInCompactCard() {
        val saturated = listOf(Color(0xFFEE355A), Color(0xFFF33E54), Color(0xFFE92A64))
        val ordinary = capsuleInlineLyricsStops(saturated)
        val immersive = capsuleInlineLyricsImmersivePanelStops(saturated)
        assertTrue(ordinary.first().red < saturated.first().red * 0.72f)
        assertTrue(immersive.first().red < saturated[1].red * 0.72f)
        assertTrue("Card still keeps a warm/red tint", immersive.first().red > immersive.first().blue)
    }

    @Test fun compactLyricsCardIsShorterButRemainsReadableOnSmallDisplays() {
        assertEquals(300f, capsuleInlineLyricsCardHeight(340.dp).value, 0.01f)
        assertEquals(444f, capsuleInlineLyricsCardHeight(600.dp).value, 0.01f)
        assertEquals(540f, capsuleInlineLyricsCardHeight(850.dp).value, 0.01f)
        assertTrue(capsuleInlineLyricsCardHeight(600.dp) < 600.dp - 60.dp)
    }

    @Test fun soundingLineUsesTheOriginalSingleTextFadeNotOverlappingGhosts() {
        val relative = "src/main/kotlin/com/nikhil/yt/ui/player/CapsuleLightLyricLine.kt"
        val source = listOf(java.io.File(relative), java.io.File("app/$relative"))
            .firstOrNull(java.io.File::isFile)?.readText()
            ?: error("CapsuleLightLyricLine source missing")
        assertTrue(source.contains("tween(FADE_OUT_MILLIS, easing = CapsuleExitEasing)"))
        assertTrue(source.contains("tween(FADE_IN_MILLIS, easing = CapsuleEnterEasing)"))
        assertTrue(source.contains("if (shown.isNotEmpty())"))
        assertFalse(source.contains("var outgoing by"))
        assertFalse(source.contains("text = outgoing"))
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
