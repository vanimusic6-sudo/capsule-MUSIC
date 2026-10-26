package com.nikhil.yt.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistScreenDesignTest {
    @Test fun artworkFadeIsContinuousClampedAndReversible() {
        val values = (0..20).map { com.nikhil.yt.ui.screens.artist.artistArtworkFade(0, it * 40, 1000, 200) }
        org.junit.Assert.assertEquals(0f, values.first(), 0f)
        org.junit.Assert.assertEquals(1f, values.last(), 0f)
        assertTrue("Portrait fade must finish before its edge reaches the controls", values[15] >= 1f)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a && b - a < 0.14f })
        org.junit.Assert.assertEquals(0f, com.nikhil.yt.ui.screens.artist.artistArtworkFade(0, -20, 1000, 200), 0f)
        org.junit.Assert.assertEquals(1f, com.nikhil.yt.ui.screens.artist.artistArtworkFade(1, 0, 1000, 200), 0f)
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
