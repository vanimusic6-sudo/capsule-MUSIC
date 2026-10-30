package com.nikhil.yt.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailToolbarRevealTest {
    private fun fade(index: Int, offset: Int, bottom: Float = 600f, bar: Float = 100f) =
        detailArtworkFadeLikeArtist(index, offset, bottom, bar)

    @Test fun fadeCompletesAtPortraitToolbarBoundaryWithoutEmptyGap() {
        assertEquals(0f, fade(0, 0), 0f)
        assertEquals(0f, fade(0, 40), 0f)
        assertEquals(0f, fade(0, 49), 0f)
        assertEquals(0f, fade(0, 60), 0f)
        assertTrue(fade(0, 90) > 0f)
        assertTrue(fade(0, 370) < 1f)
        assertTrue("Photo should remain until the portrait approaches the toolbar", fade(0, 450) < 1f)
        assertTrue("Avoid the old premature 14%-of-photo black area", fade(0, 480) < 1f)
        assertEquals(1f, fade(0, 495), 0f)
        assertEquals(1f, fade(1, 0), 0f)
        assertEquals(0f, fade(0, 100, Float.NaN), 0f)
    }

    @Test fun scrollFadeIsSmoothMonotonicAndReversible() {
        val values = (0..500 step 2).map { fade(0, it) }
        assertTrue(values.zipWithNext().all { (a, b) ->
            b >= a && b - a < .025f
        })
        val mid = fade(0, 278)
        assertTrue(mid in .45f.. .55f)
        assertEquals(mid, fade(0, 278), 0f)
        assertTrue(fade(0, 340) > mid)
        assertEquals(1f, detailToolbarBackdropAlpha(1f), 0f)
    }

    @Test fun artistAndDetailNowShareExactlyOneMotionCurve() {
        for (height in listOf(420, 650, 900)) {
            for (offset in 0..height step 3) {
                val detail = detailArtworkFadeLikeArtist(0, offset, height.toFloat() + 100f, 100f)
                val artist = com.nikhil.yt.ui.screens.artist.artistArtworkFade(
                    0, offset, height + 100, 100,
                )
                assertEquals("Artist/detail mismatch at height=$height, offset=$offset",
                    artist, detail, 0f)
            }
        }
    }

    @Test fun albumsAndPlaylistsUseArtistScrollDistanceInsteadOfTallerCover() {
        for (offset in 0..550 step 5) {
            val artist = com.nikhil.yt.ui.screens.artist.artistArtworkFade(
                0, offset, 600, 100,
            )
            val detail = detailArtistLengthArtworkFade(
                firstVisibleItemIndex = 0,
                offsetPx = offset,
                artistArtworkBottomPx = 600f,
                detailArtworkBottomPx = 680f,
                toolbarBottomPx = 100f,
            )
            assertEquals("Same fade at offset=$offset despite larger album photo",
                artist, detail, 0f)
        }
        // On narrow / short screens never let the cover pass behind an
        // unpainted bar while the longer artist-distance fade is unfinished.
        assertEquals(1f, detailArtistLengthArtworkFade(0, 440, 600f, 540f, 100f), 0f)
        assertEquals(1f, detailArtistLengthArtworkFade(1, 0, 600f, 680f, 100f), 0f)
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
