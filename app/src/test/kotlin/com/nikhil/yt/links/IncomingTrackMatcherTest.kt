package com.nikhil.yt.links

import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingTrackMatcherTest {
    private fun song(id: String, title: String, artist: String, explicit: Boolean = false) =
        SongItem(
            id = id,
            title = title,
            artists = listOf(Artist(name = artist, id = null)),
            thumbnail = "",
            explicit = explicit,
        )

    @Test fun uniqueTitleAndArtistCanAutoplay() {
        val target = song("dQw4w9WgXcQ", "  Sample - Song! ", " Example Artist ")
        assertEquals(
            target,
            IncomingTrackMatcher.uniqueExactMatch(
                "Sample Song", "Example Artist",
                listOf(song("jNQXAC9IVRw", "Sample Song", "Other Artist"), target),
            ),
        )
    }

    @Test fun multipleRecordingsOrMissingArtistNeverAutoplay() {
        val a = song("dQw4w9WgXcQ", "Example Song", "Singer")
        val b = song("jNQXAC9IVRw", "Example Song", "Singer")
        assertNull(IncomingTrackMatcher.uniqueExactMatch("Example Song", "Singer", listOf(a, b)))
        assertNull(IncomingTrackMatcher.uniqueExactMatch("Example Song", null, listOf(a)))
        assertNull(IncomingTrackMatcher.uniqueExactMatch("Example Song", "Someone Else", listOf(a)))
        assertEquals(a, IncomingTrackMatcher.uniqueExactMatch("Example Song", "Singer", listOf(a, a)))
    }

    @Test fun explicitTracksRespectThePreference() {
        val explicit = song("dQw4w9WgXcQ", "Example Song", "Singer", explicit = true)
        assertNull(IncomingTrackMatcher.uniqueExactMatch("Example Song", "Singer", listOf(explicit), allowExplicit = false))
        assertEquals(explicit, IncomingTrackMatcher.uniqueExactMatch("Example Song", "Singer", listOf(explicit)))
    }
}
