package com.nikhil.yt.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailToolbarRevealTest {
    private fun fade(index: Int, offset: Int, bottom: Float = 600f, bar: Float = 100f) =
        detailArtworkFadeLikeArtist(index, offset, bottom, bar)

    @Test fun fadeStartsLaterAndFinishesJustBeforePhotoReachesToolbar() {
        assertEquals(0f, fade(0, 0), 0f)
        assertEquals(0f, fade(0, 60), 0f) // 12% of 500px
        assertEquals(0f, fade(0, 59), 0f)
        assertTrue(fade(0, 90) > 0f)
        assertTrue(fade(0, 370) < 1f) // Former 74% threshold was too early
        assertTrue(fade(0, 450) < 1f)
        assertEquals(1f, fade(0, 490), 0f)
        assertEquals(1f, fade(1, 0), 0f)
        assertEquals(0f, fade(0, 100, Float.NaN), 0f)
    }

    @Test fun scrollFadeIsSmoothMonotonicAndReversible() {
        val values = (0..500 step 2).map { fade(0, it) }
        assertTrue(values.zipWithNext().all { (a, b) ->
            b >= a && b - a < .025f
        })
        val mid = fade(0, 270)
        assertTrue(mid in .45f.. .55f)
        assertEquals(mid, fade(0, 270), 0f)
        assertEquals(1f, detailToolbarBackdropAlpha(1f), 0f)
    }

    @Test fun toolbarSurfaceNeverDoubleTintsVisiblePhoto() {
        for (i in 0 until 1000) {
            val alpha = i / 1000f
            assertEquals(
                artistToolbarSurfaceAlpha(alpha),
                detailToolbarBackdropAlpha(alpha),
                0f,
            )
        }
        assertEquals(1f, detailToolbarBackdropAlpha(0f, forceOpaque = true), 0f)
    }
}
