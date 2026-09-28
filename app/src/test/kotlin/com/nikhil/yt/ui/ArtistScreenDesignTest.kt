package com.nikhil.yt.ui

import com.nikhil.yt.ui.screens.artist.artistToolbarOverArtwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistScreenDesignTest {
    @Test
    fun `artist name appears as soon as portrait clears the toolbar`() {
        val artworkHeightPx = 980
        val toolbarBottomPx = 240
        val collapseAtPx = artworkHeightPx - toolbarBottomPx

        assertTrue(
            artistToolbarOverArtwork(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = collapseAtPx - 1,
                artworkHeightPx = artworkHeightPx,
                toolbarBottomPx = toolbarBottomPx,
            ),
        )
        assertFalse(
            artistToolbarOverArtwork(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = collapseAtPx,
                artworkHeightPx = artworkHeightPx,
                toolbarBottomPx = toolbarBottomPx,
            ),
        )
        assertFalse(
            artistToolbarOverArtwork(
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffsetPx = 0,
                artworkHeightPx = artworkHeightPx,
                toolbarBottomPx = toolbarBottomPx,
            ),
        )
    }
}
