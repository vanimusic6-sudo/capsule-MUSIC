package com.nikhil.yt.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SquareArtworkCropTest {
    @Test fun enabledSquareCardCropsWideVideoArtRegardlessOfItsCdnHost() {
        // The old ytimg.com URL gate silently disabled this for non-YouTube sources.
        assertTrue(shouldCropSquareThumbnail(true, 1f))
        assertTrue(shouldCropSquareThumbnail(true, 1.0001f))
    }

    @Test fun preferenceOffRetainsFitAndWideCardsDoNotChangeGeometry() {
        assertFalse(shouldCropSquareThumbnail(false, 1f))
        assertFalse(shouldCropSquareThumbnail(true, 16f / 9f))
        assertFalse(shouldCropSquareThumbnail(true, 4f / 3f))
    }

    @Test fun malformedRatiosAreNotConsideredSquare() {
        assertFalse(shouldCropSquareThumbnail(true, Float.NaN))
        assertFalse(shouldCropSquareThumbnail(true, Float.POSITIVE_INFINITY))
        assertFalse(shouldCropSquareThumbnail(true, 0f))
    }
}
