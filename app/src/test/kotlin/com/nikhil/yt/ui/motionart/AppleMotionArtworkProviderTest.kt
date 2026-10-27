package com.nikhil.yt.ui.motionart

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppleMotionArtworkProviderTest {
    @Test fun extractsPortraitArtistAndSquareAlbumSeparately() {
        val attrs = JSONObject("""
          {"editorialVideo":{
            "motionDetailTall":{"video":"https://example.apple.com/artist.m3u8"},
            "motionDetailSquare":{"video":"https://example.apple.com/album.m3u8"}
          }}
        """.trimIndent())
        assertEquals("https://example.apple.com/artist.m3u8",
            AppleMotionArtworkProvider.extractUrl(attrs, "motionDetailTall"))
        assertEquals("https://example.apple.com/album.m3u8",
            AppleMotionArtworkProvider.extractUrl(attrs, "motionDetailSquare"))
    }

    @Test fun rejectsInsecureAndUnknownMotionHosts() {
        assertFalse(AppleMotionArtworkProvider.validVideoUrl("http://example.apple.com/clip.m3u8"))
        assertFalse(AppleMotionArtworkProvider.validVideoUrl("https://evil.apple.com.attacker.tld/clip.m3u8"))
        assertFalse(AppleMotionArtworkProvider.validVideoUrl("https://example.apple.com/file.html"))
        assertTrue(AppleMotionArtworkProvider.validVideoUrl("https://example.apple.com/file.mp4"))
    }

    @Test fun absentMotionNeverReusesArtistAsAlbum() {
        val noVideo = JSONObject("""{"editorialVideo":{"motionDetailTall":{"image":"abc"}}}""")
        assertNull(AppleMotionArtworkProvider.extractUrl(noVideo, "motionDetailTall"))
    }
}
