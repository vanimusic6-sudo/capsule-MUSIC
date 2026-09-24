package com.nikhil.yt.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalTrackMetadataTest {
    @Test fun spotifyArtistFromPublicDescription() {
        val html = """<html><head>
            <meta property="og:description" content="Never Gonna Give You Up, a song by Rick Astley on Spotify">
            </head></html>"""
        assertEquals("Rick Astley", ExternalTrackMetadata.spotifyArtistFromPage(html))
    }

    @Test fun spotifyArtistFromStructuredDescription() {
        val html = """<html><head>
            <meta property="og:description" content="Never Gonna Give You Up · Song · Rick Astley · 1987">
            </head></html>"""
        assertEquals("Rick Astley", ExternalTrackMetadata.spotifyArtistFromPage(html))
    }

    @Test fun genericPageDoesNotInventArtist() {
        assertNull(ExternalTrackMetadata.spotifyArtistFromPage("<html><head><title>Spotify</title></head></html>"))
        assertNull(ExternalTrackMetadata.spotifyArtistFromPage(
            """<meta property="og:description" content="A collection of music on Spotify">"""
        ))
    }
}
