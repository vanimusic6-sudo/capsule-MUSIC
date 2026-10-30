package com.nikhil.yt.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistScreenDesignTest {
    @Test fun artworkFadeIsContinuousClampedAndReversible() {
        val values = (0..20).map { com.nikhil.yt.ui.screens.artist.artistArtworkFade(0, it * 40, 1000, 200) }
        org.junit.Assert.assertEquals(0f, values.first(), 0f)
        org.junit.Assert.assertEquals(1f, values.last(), 0f)
        assertTrue("Artist portrait should remain visible at 75% travel", values[15] < 1f)
        assertTrue("Shorter dissolve still starts after the initial scroll", values[1] == 0f)
        assertTrue("The dissolve begins as the artwork starts approaching the bar", values[3] > 0f)
        assertTrue("Fade completes before the portrait crosses the bar", values[20] >= 1f)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a && b - a < 0.14f })
        org.junit.Assert.assertEquals(0f, com.nikhil.yt.ui.screens.artist.artistArtworkFade(0, -20, 1000, 200), 0f)
        org.junit.Assert.assertEquals(1f, com.nikhil.yt.ui.screens.artist.artistArtworkFade(1, 0, 1000, 200), 0f)
    }

    @Test fun toolbarSealsCloseToActionsWithoutCoveringThem() {
        // The old 14%-of-photo safety band made the bar solid 50-80dp too
        // soon, leaving an empty black strip. The photo itself is unchanged.
        for (widthDp in listOf(320, 360, 393, 450)) {
            val portraitBottom = (widthDp * 1.25f).toInt()
            val firstActionTop = portraitBottom + 12 // photo ends, then 12dp gap
            val toolbarBottom = 88 // common 24dp safe top + 64dp bar
            val sealedAt = (0..portraitBottom).first {
                com.nikhil.yt.ui.screens.artist.artistArtworkFade(
                    0, it, portraitBottom, toolbarBottom,
                ) >= 1f
            }
            val gap = firstActionTop - sealedAt - toolbarBottom
            assertTrue("Toolbar must seal before the action rail enters: gap=$gap", gap >= 12)
            assertTrue("No oversized empty black strip before buttons: gap=$gap", gap <= 22)
        }
    }

    @Test fun artistToolbarFadeNeverDoubleTintsTheVisiblePortrait() {
        // The hero already fades into the same background. Blending a second
        // 50%-opaque toolbar over it produces a much darker strip (alpha
        // compositing squares the remaining photo: (1 - 0.5)^2).
        val alpha: (Float) -> Float = { com.nikhil.yt.ui.component.artistToolbarSurfaceAlpha(it) }
        org.junit.Assert.assertEquals(0f, alpha(0f), 0.00001f)
        for (fraction in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.95f)) {
            org.junit.Assert.assertEquals("No overlay while photograph is visible",
                0f, alpha(fraction), 0f)
        }
        org.junit.Assert.assertEquals(1f, alpha(1f), 0.00001f)
        val samples = (0..1000).map { alpha(it / 1000f) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a })
        org.junit.Assert.assertEquals(0f, alpha(0.985f), 0f)
        org.junit.Assert.assertEquals(0f, alpha(0.99f), 0f)
        assertTrue(samples.all { it in 0f..1f })
    }
}
