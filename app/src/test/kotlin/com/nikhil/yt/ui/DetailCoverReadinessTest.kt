package com.nikhil.yt.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.Density
import com.nikhil.yt.ui.component.DetailHeaderMotionState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reproduces the cached-cover/late-readiness-reset blackout at scroll origin. */
class DetailCoverReadinessTest {
    @Test fun asyncCoverStateMustNeverHideAPhotoAtTopOfTheList() {
        val motion = DetailHeaderMotionState(LazyListState(), Density(1f))
        // Before the first Coil callback the photo should become visible as
        // soon as AsyncImage has pixels; this value is scroll-only.
        assertEquals(1f, motion.artworkAlpha(), 0f)
        motion.setArtworkReady(true)
        assertEquals(1f, motion.artworkAlpha(), 0f)
        // Old code could issue this late reset after Coil success and leave
        // a fully decoded bitmap permanently black.
        motion.setArtworkReady(false)
        assertEquals(1f, motion.artworkAlpha(), 0f)
        assertEquals(0f, motion.toolbarTitleAlpha(), 0f)
    }
}
