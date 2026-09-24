package com.nikhil.yt.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingTrackLinksTest {
    @Test fun youtubeMusicAndRegularLinksHaveTheSameId() {
        val id = "dQw4w9WgXcQ"
        val examples = listOf(
            "https://music.youtube.com/watch?v=$id&list=RDAMVM$id",
            "https://www.youtube.com/watch?v=$id&t=17",
            "https://youtu.be/$id?si=tracking",
            "https://youtube.com/shorts/$id",
            "https://youtube.com/live/$id",
            "https://m.youtube.com/watch?v=$id",
            "Watch this https://music.youtube.com/watch?v=$id&si=anything",
        )
        examples.forEach { text ->
            assertEquals(id, (IncomingTrackLinks.parse(text) as IncomingTrackLink.YouTube).videoId)
        }
        assertEquals("RDAMVM$id", (IncomingTrackLinks.parse(examples.first()) as IncomingTrackLink.YouTube).playlistId)
    }

    @Test fun recognizesSpotifyAndSoundCloudTracksIncludingSharedText() {
        val id = "4uLU6hMCjMI75M1A2tKUQC"
        val spotify = IncomingTrackLinks.parse("Listen on Spotify: https://open.spotify.com/track/$id?si=xyz")
        assertEquals("https://open.spotify.com/track/$id", (spotify as IncomingTrackLink.External).url)
        assertEquals(IncomingTrackLink.Provider.SPOTIFY, spotify.provider)
        val localized = IncomingTrackLinks.parse("https://open.spotify.com/intl-de/track/$id")
        assertEquals(spotify, localized)
        assertEquals(spotify, IncomingTrackLinks.parse("spotify:track:$id"))
        val soundcloud = IncomingTrackLinks.parse("Take a listen: https://soundcloud.com/artist-name/track-name?utm_source=clipboard")
        assertEquals(IncomingTrackLink.Provider.SOUNDCLOUD, (soundcloud as IncomingTrackLink.External).provider)
        assertTrue(IncomingTrackLinks.parse("https://spotify.link/abc123") is IncomingTrackLink.External)
        assertTrue(IncomingTrackLinks.parse("https://on.soundcloud.com/abc123") is IncomingTrackLink.External)
    }

    @Test fun ignoresOtherDomainsAndNonTrackPages() {
        assertNull(IncomingTrackLinks.parse("https://evil-youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(IncomingTrackLinks.parse("https://youtube.com/playlist?list=PL123"))
        assertNull(IncomingTrackLinks.parse("https://open.spotify.com/album/4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(IncomingTrackLinks.parse("https://soundcloud.com/artist-name/sets/playlist"))
        assertNull(IncomingTrackLinks.parse("https://soundcloud.com/artist-name"))
        assertNull(IncomingTrackLinks.parse("hello world"))
    }
}
