package com.nikhil.yt.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailToolbarRevealTest {
    private fun fraction(offset: Int, index: Int = 0, force: Boolean = false) =
        detailToolbarRevealFraction(
            firstVisibleItemIndex = index,
            firstVisibleItemScrollOffset = offset,
            headerHeightPx = 1000,
            fadeDistancePx = 200,
            toolbarHeightPx = 100,
            forceVisible = force,
        )

    @Test fun revealStartsBeforeHeaderDisappearsAndIsContinuous() {
        assertEquals(0f, fraction(699), 0f)
        assertEquals(0f, fraction(700), 0f)
        assertTrue(fraction(740) > 0f)
        assertEquals(.5f, fraction(800), 0.0001f)
        assertEquals(1f, fraction(900), 0f)
        assertEquals(1f, fraction(0, index = 1), 0f)
    }

    @Test fun enteringAndLeavingRevealHaveNoHardFirstFrameJump() {
        assertTrue(fraction(701) < 0.001f)
        assertTrue(1f - fraction(899) < 0.001f)
        assertTrue(fraction(825) > fraction(775))
    }

    @Test fun initialUnmeasuredHeaderNeverFlashesTheTitle() {
        assertEquals(0f, detailToolbarRevealFraction(0, 0, 0, 200, 100, false), 0f)
    }

    @Test fun artworkWaitsForTitleApproachAndFadesAcrossFullThreeHundredPixels() {
        fun cover(offset: Int, index: Int = 0) =
            detailArtworkFadeFraction(index, offset, artworkBottomPx = 600, toolbarBottomPx = 100)
        // Title crossing: 600 + 10 - 100 = 510 px.
        // The 300px dissolve begins at 306px and ends at 606px.
        assertEquals(0f, cover(0), 0f)
        assertEquals(0f, cover(305), 0f)
        assertEquals(0f, cover(306), 0f)
        assertEquals(0.5f, cover(456), 0.0001f)
        assertEquals(1f, cover(606), 0f)
        assertTrue(cover(400) < cover(500))
        assertEquals(1f, cover(0, index = 1), 0f)
        assertEquals(0f, detailArtworkFadeFraction(0, 0, 0, 100), 0f)
    }

    @Test fun toolbarIsContinuousAndItsTitleDoesNotPrecedeOriginalHeaderTitle() {
        val earlier = detailArtworkFadeFraction(0, 400, 600, 100)
        val crossing = detailArtworkFadeFraction(0, 510, 600, 100)
        val finalFade = detailArtworkFadeFraction(0, 606, 600, 100)
        assertEquals(0f, detailToolbarSurfaceAlpha(0f), 0f)
        assertEquals(1f, detailToolbarSurfaceAlpha(1f), 0f)
        assertTrue(detailToolbarSurfaceAlpha(earlier) > 0f)
        assertTrue(detailToolbarSurfaceAlpha(earlier) < detailToolbarSurfaceAlpha(crossing))
        assertEquals(0f, detailToolbarTitleAlpha(earlier), 0f)
        assertTrue(detailToolbarTitleAlpha(crossing) > 0f)
        assertTrue(detailToolbarTitleAlpha(crossing) < 1f)
        assertEquals(1f, detailToolbarTitleAlpha(finalFade), 0f)
        val before = detailToolbarSurfaceAlpha(detailArtworkFadeFraction(0, 605, 600, 100))
        assertTrue("No one-frame opaque toolbar switch", 1f - before < 0.001f)
    }

    @Test fun forcedToolbarAndAbsentHeroAreAlwaysVisible() {
        assertEquals(1f, fraction(0, force = true), 0f)
        assertEquals(
            1f,
            detailToolbarRevealFraction(0, 0, 0, 200, 100, true),
            0f,
        )
    }
}
