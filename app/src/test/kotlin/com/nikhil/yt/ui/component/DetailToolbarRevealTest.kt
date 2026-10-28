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

    @Test fun coverFadesSmoothlyAndToolbarWaitsUntilItReachesPageColour() {
        fun cover(offset: Int, index: Int = 0) =
            detailArtworkFadeFraction(index, offset, artworkBottomPx = 600, toolbarBottomPx = 100)
        assertEquals(0f, cover(0), 0f)
        assertEquals(0.5f, cover(185), 0.0001f)
        assertEquals(1f, cover(370), 0f)
        assertEquals(1f, cover(0, index = 1), 0f)
        assertEquals(0f, detailToolbarSurfaceAlpha(cover(184)), 0f)
        assertEquals(1f, detailToolbarSurfaceAlpha(cover(370)), 0f)
        assertEquals(0f, detailArtworkFadeFraction(0, 0, 0, 100), 0f)
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
