package com.nikhil.yt.ui.motionart

import android.app.Application
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
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
        assertTrue(AppleMotionArtworkProvider.validVideoUrl("https://is1-ssl.mzstatic.com/motion.m3u8"))
    }

    @Test fun absentMotionNeverReusesArtistAsAlbum() {
        val noVideo = JSONObject("""{"editorialVideo":{"motionDetailTall":{"image":"abc"}}}""")
        assertNull(AppleMotionArtworkProvider.extractUrl(noVideo, "motionDetailTall"))
    }
}
