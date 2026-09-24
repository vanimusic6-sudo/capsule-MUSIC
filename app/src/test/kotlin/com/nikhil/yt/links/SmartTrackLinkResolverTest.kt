package com.nikhil.yt.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTrackLinkResolverTest {
    private val example = "https://music.sk-lane.com/qMyvxk1GLAJuVJHo"

    @Test fun youtubeLinkOnMusicPageResolvesToExactVideoId() {
        val html = """<html><head><meta property="og:title" content="Example track — SK Lane"></head>
            <body><a href="https://music.youtube.com/watch?v=dQw4w9WgXcQ">YouTube Music</a>
            <a href="https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC">Spotify</a></body></html>"""
        val result = SmartTrackLinkResolver.parseLandingPage(html, example)
        assertEquals(
            IncomingTrackLink.YouTube("dQw4w9WgXcQ", null),
            (result as SmartTrackResolution.Direct).link,
        )
    }

    @Test fun multipleYoutubeRecordingsDoNotPickRandomOne() {
        val html = """<html><head><meta property="og:title" content="Example title | SK Lane"></head>
            <body><a href="https://youtu.be/dQw4w9WgXcQ">Version A</a>
            <a href="https://youtu.be/jNQXAC9IVRw">Version B</a></body></html>"""
        assertEquals(
            SmartTrackResolution.Search("Example title"),
            SmartTrackLinkResolver.parseLandingPage(html, example),
        )
    }

    @Test fun loneSpotifyLinkUsesExistingExternalSearchFlow() {
        val html = """<html><body>
            <a href="https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC">Spotify</a>
            </body></html>"""
        val result = SmartTrackLinkResolver.parseLandingPage(html, example)
        assertTrue((result as SmartTrackResolution.Direct).link is IncomingTrackLink.External)
    }

    @Test fun unsupportedOrUnrelatedPagesAreNotTreatedAsTracks() {
        assertNull(SmartTrackLinkResolver.parseLandingPage(
            """<html><head><title>Homepage</title></head><body>Listen!</body></html>""",
            example,
        ))
        assertNull(SmartTrackLinkResolver.parseLandingPage(
            """<html><a href="https://music.youtube.com/watch?v=dQw4w9WgXcQ">song</a></html>""",
            "https://wrong.example/song",
        ))
    }
}
