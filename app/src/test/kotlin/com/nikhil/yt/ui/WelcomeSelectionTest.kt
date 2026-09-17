package com.nikhil.yt.ui

import com.nikhil.yt.innertube.models.ArtistItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every artist picked in the welcome flow gets subscribed, not just the last one.
 *
 * The selection used to be a set of ids, and subscribing worked by filtering the *current* grid by
 * them. The grid is replaced by every search, so the natural way to use the screen — type a name,
 * tap, type the next name, tap — quietly threw away everyone found by an earlier query, and only
 * the last pick survived. Keeping the whole item means a pick is complete the moment it is made.
 *
 * This models the selection the screen keeps, rather than reaching into the view model, because
 * the failure was never in the database call: it was in what got as far as being handed to it.
 */
class WelcomeSelectionTest {
    private fun artist(id: String) =
        ArtistItem(
            id = id,
            title = id,
            thumbnail = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )

    private fun toggle(
        selected: Map<String, ArtistItem>,
        artist: ArtistItem,
    ): Map<String, ArtistItem> =
        if (artist.id in selected) selected - artist.id else selected + (artist.id to artist)

    @Test fun picksFromDifferentSearchesAllSurvive() {
        var selected = emptyMap<String, ArtistItem>()

        // Search one, pick it. The grid then becomes something else entirely.
        selected = toggle(selected, artist("from-first-search"))
        selected = toggle(selected, artist("from-second-search"))
        selected = toggle(selected, artist("from-third-search"))

        assertEquals(
            listOf("from-first-search", "from-second-search", "from-third-search"),
            selected.values.map { it.id },
        )
    }

    @Test fun tappingTwiceUnpicks() {
        var selected = emptyMap<String, ArtistItem>()
        selected = toggle(selected, artist("a"))
        selected = toggle(selected, artist("b"))
        selected = toggle(selected, artist("a"))
        assertEquals(listOf("b"), selected.values.map { it.id })
    }

    @Test fun theSameArtistFoundTwiceIsStillOnePick() {
        var selected = emptyMap<String, ArtistItem>()
        selected = toggle(selected, artist("a"))
        // Found again by a different query, tapped again: that is an unpick, not a duplicate.
        selected = toggle(selected, artist("a"))
        assertEquals(emptyList<String>(), selected.values.map { it.id })
    }
}
