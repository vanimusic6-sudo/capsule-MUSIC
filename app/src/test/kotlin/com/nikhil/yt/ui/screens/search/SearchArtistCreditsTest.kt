package com.nikhil.yt.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchArtistCreditsTest {
    @Test
    fun `Top result is credited only to artists of this track`() {
        assertEquals(
            listOf("First Artist", "Guest", "Second Guest"),
            mergeSearchArtistCredits(
                listOf("First Artist", "Guest"),
                listOf("first artist", "Second Guest"),
                listOf(" Guest "),
            )
        )
    }

    @Test
    fun `one supplied artist remains one artist`() {
        assertEquals(listOf("MZLFF"), mergeSearchArtistCredits(listOf("MZLFF")))
        assertEquals(emptyList<String>(), mergeSearchArtistCredits(emptyList(), listOf("", "  ")))
    }
}
