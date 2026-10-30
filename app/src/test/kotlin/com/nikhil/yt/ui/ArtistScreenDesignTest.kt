package com.nikhil.yt.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistScreenDesignTest {
    @Test fun artworkFadeIsContinuousClampedAndReversible() {
        val values = (0..80).map { com.nikhil.yt.ui.screens.artist.artistArtworkFade(0, it * 10, 1000, 200) }
        org.junit.Assert.assertEquals(0f, values.first(), 0f)
        org.junit.Assert.assertEquals(1f, values.last(), 0f)
        // The pinned bar takes up 200px: its exact transition band is
        // scroll 599..799, not a guessed 12..99% of the 800px distance.
        assertTrue("Keep the original portrait visible before toolbar crossing", values[59] == 0f)
        assertTrue("Dissolve only in the measured bar-height window", values[65] > 0f)
        assertTrue("Near the edge, photo must still be fading", values[78] < 1f)
        assertTrue("Seal as last photo pixel approaches bar", values[80] == 1f)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a && b - a < 0.10f })
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
