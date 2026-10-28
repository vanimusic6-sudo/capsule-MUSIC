package com.nikhil.yt.ui.component

import com.nikhil.yt.ui.screens.artist.artistArtworkFade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailToolbarRevealTest {
    private fun fade(index: Int, offset: Int, artBottom: Float = 600f, barBottom: Float = 100f) =
        detailArtworkFadeLikeArtist(index, offset, artBottom, barBottom)

    @Test fun albumAndPlaylistArtworkFollowTheExactArtistScrollCurve() {
        for (offset in -200..800 step 4) {
            assertEquals(
                "Artist parity at scroll=$offset",
                artistArtworkFade(0, offset, 600, 100),
                fade(0, offset),
                0.0001f,
            )
        }
        assertEquals(0f, fade(0, 0), 0f)
        assertEquals(1f, fade(1, 0), 0f)
        assertEquals(1f, fade(0, 370), 0f)
    }

    @Test fun dissolveIsContinuousAndReversibleWithoutTitleHandoff() {
        val values = (0..370 step 5).map { fade(0, it) }
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a && b - a < .04f })
        assertEquals(0f, values.first(), 0f)
        assertEquals(1f, values.last(), 0f)
        for (offset in listOf(16, 84, 181, 329)) {
            assertEquals(fade(0, offset), fade(0, offset), 0f)
        }
        assertEquals(0f, fade(0, 100, Float.NaN), 0f)
    }

    @Test fun toolbarPaintsSameSurfaceOnlyAfterPhotoDisappears() {
        for (i in 0 until 1000) {
            val a = i / 1000f
            assertEquals(artistToolbarSurfaceAlpha(a), detailToolbarBackdropAlpha(a), 0f)
        }
        assertEquals(1f, detailToolbarBackdropAlpha(1f), 0f)
        assertEquals(1f, detailToolbarBackdropAlpha(0f, forceOpaque = true), 0f)
    }
}
