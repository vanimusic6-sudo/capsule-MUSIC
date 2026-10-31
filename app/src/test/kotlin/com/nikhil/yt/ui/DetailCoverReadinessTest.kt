package com.nikhil.yt.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.Density
import com.nikhil.yt.ui.component.DetailHeaderMotionState
import com.nikhil.yt.ui.component.DetailArtworkRequestState
import com.nikhil.yt.ui.component.detailToolbarBackdropAlpha
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reproduces the cached-cover/late-readiness-reset blackout at scroll origin. */
class DetailCoverReadinessTest {
    @Test fun asyncCoverStateMustNeverHideAPhotoAtTopOfTheList() {
        val motion = DetailHeaderMotionState(LazyListState(), Density(1f))
        // Before the first Coil callback the photo should become visible as
        // soon as AsyncImage has pixels; this value is scroll-only.
        assertEquals(1f, motion.artworkAlpha(), 0f)
        val request = DetailArtworkRequestState(listOf("cached-cover"))
        val cached = request.attempt!!
        request.succeed(cached)
        request.updateCandidates(listOf("new-track-fallback", "cached-cover"))
        request.fail(cached) // A late timeout/error cannot clear success.
        assertEquals(cached, request.attempt)
        assertEquals(true, request.loaded)
        assertEquals(1f, motion.artworkAlpha(), 0f)
        assertEquals(0f, motion.toolbarTitleAlpha(), 0f)
        assertEquals("A decoded cover must extend UNDER the toolbar",
            0f, detailToolbarBackdropAlpha(motion.artworkFade()), 0f)
    }
}
