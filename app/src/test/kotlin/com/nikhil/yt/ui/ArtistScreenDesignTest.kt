package com.nikhil.yt.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistScreenDesignTest {
    @Test fun artworkFadeIsContinuousClampedAndReversible() {
        val values = (0..20).map { com.nikhil.yt.ui.screens.artist.artistArtworkFade(0, it * 40, 1000, 200) }
        org.junit.Assert.assertEquals(0f, values.first(), 0f)
        org.junit.Assert.assertEquals(1f, values.last(), 0f)
        org.junit.Assert.assertEquals(0.5f, values[10], 0.001f)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a && b - a < 0.1f })
        org.junit.Assert.assertEquals(0f, com.nikhil.yt.ui.screens.artist.artistArtworkFade(0, -20, 1000, 200), 0f)
        org.junit.Assert.assertEquals(1f, com.nikhil.yt.ui.screens.artist.artistArtworkFade(1, 0, 1000, 200), 0f)
    }

    @Test fun artistToolbarFadeHasNoHardThreshold() {
        // The fade is continuous and finishes as the portrait disappears (not at 80%).
        val values = (0..100).map { com.nikhil.yt.ui.component.artistToolbarSurfaceAlpha(it / 100f) }
        org.junit.Assert.assertEquals(0f, values.first(), 0.0001f)
        org.junit.Assert.assertEquals(1f, values.last(), 0.0001f)
        org.junit.Assert.assertEquals(0f, values[80], 0.0001f)
        org.junit.Assert.assertEquals(1f, values[100], 0.0001f)
        org.junit.Assert.assertTrue(values.zipWithNext().all { (a, b) -> b >= a && b-a <= 0.16f })
    }

}
