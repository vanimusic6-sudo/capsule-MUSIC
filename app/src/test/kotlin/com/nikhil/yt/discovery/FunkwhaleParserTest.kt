package com.nikhil.yt.discovery

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunkwhaleParserTest {
    @Test fun v2GuidNameArtistCreditAndCoverAreRecognized() {
        val rows = JSONArray("""[{
            "guid":"6b7cd099-8431-4fee-a61a-36ddace4311a",
            "name":"Open indie recording",
            "playable":true,
            "artistCredit":[{"name":"Creator","guid":"e816c74f-2e10-4d0d-9abb-a9dff415ea22"}],
            "cover":{"urls":{"original":"https://open.audio/media/cover.jpg"}},
            "uploads":[{"uuid":"b927e90a-a2d6-4fc8-aa4f-f4dcd515260d"}]
        }]""")
        val tracks = FunkwhaleMusic.parse(rows)
        assertEquals(1, tracks.size)
        assertEquals("Open indie recording", tracks[0].title)
        assertEquals("Creator", tracks[0].artist)
        assertTrue(tracks[0].playable)
        assertTrue(tracks[0].streamUrl!!.contains("/api/v2/listen/b927e90a-"))
        assertFalse(tracks[0].downloadable)
    }

    @Test fun missingPublicUploadKeepsPublisherPageWithoutInventingPlayback() {
        val rows = JSONArray("""[{
            "guid":"6b7cd099-8431-4fee-a61a-36ddace4311a",
            "name":"Unstreamable but discoverable",
            "artistCredit":[{"name":"Composer"}],
            "playable":true
        }]""")
        val track = FunkwhaleMusic.parse(rows).single()
        assertNull(track.streamUrl)
        assertTrue(track.sourcePage.startsWith("https://open.audio/"))
    }

    @Test fun legacyIdsAndRestrictedTracksAreHandledWithoutLeakingAccess() {
        val rows = JSONArray("""[
          {"id":99,"title":"Legacy","artist":{"name":"Music Maker"},
           "is_playable":true,"uploads":[{"uuid":"b927e90a-a2d6-4fc8-aa4f-f4dcd515260d"}]},
          {"guid":"6b7cd099-8431-4fee-a61a-36ddace4311a",
           "name":"Private","playable":false}
        ]""")
        val tracks = FunkwhaleMusic.parse(rows)
        assertEquals(1, tracks.size)
        assertEquals("Music Maker", tracks[0].artist)
        assertTrue(tracks[0].streamUrl!!.contains("/api/v1/listen/"))
    }
}
