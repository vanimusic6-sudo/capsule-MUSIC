package com.nikhil.yt.canvas

import org.junit.Assert.*
import org.junit.Test

class CapsuleMotionArtworkContractTest {
    @Test fun appleReturnsAllowedVerticalMp4AndChecksIdentity() {
        val response = """{"name":"Nights","artist":"Frank Ocean","albumId":"1146195596","videoUrl":"https://mvod.itunes.apple.com/wide.mp4","videoUrlVertical":"https://mvod.itunes.apple.com/vertical.mp4"}"""
        val clip = capsuleParseAppleMotion(response, "Nights", "Frank Ocean")
        assertEquals(CapsuleMotionSource.APPLE_ARTWORK, clip?.source)
        assertTrue(clip!!.uri.endsWith("vertical.mp4"))
        assertNull(capsuleParseAppleMotion(response, "Lost", "Frank Ocean"))
        assertNull(capsuleParseAppleMotion(response, "Nights", "Other Artist"))
    }

    @Test fun appleHlsAndPlainYoutubeTitlesWork() {
        val response = """{"name":"Hurt","artist":"Oliver Tree","animated":"https://play.itunes.apple.com/audio.m3u8"}"""
        val clip = capsuleParseAppleMotion(response, "Hurt (Official Video)", "Oliver Tree")
        assertTrue(clip?.isHls == true)
        assertEquals("hurt", capsuleMotionSongIdentity("Hurt [Official Video]"))
    }

    @Test fun spotifyCanvasRequiresRealTrackIdAndTrustedCdn() {
        val clip = capsuleParseSpotifyMotion(
            """{"trackId":"39LLxExYz6ewLAcYrzQQyP","videoUrl":"https://canvaz.scdn.co/loop.cnvs.mp4"}"""
        )
        assertNotNull(clip)
        assertEquals(CapsuleMotionSource.SPOTIFY_CANVAS, clip?.source)
        assertNull(capsuleParseSpotifyMotion("""{"trackId":"bad","videoUrl":"https://canvaz.scdn.co/a.mp4"}"""))
        assertNull(capsuleParseSpotifyMotion("""{"trackId":"39LLxExYz6ewLAcYrzQQyP","videoUrl":null}"""))
    }

    @Test fun maliciousAndUnavailableVideoLinksAreRejected() {
        val apple = CapsuleMotionSource.APPLE_ARTWORK
        val spotify = CapsuleMotionSource.SPOTIFY_CANVAS
        assertNull(capsuleMotionSafeUrl("http://mvod.itunes.apple.com/clip.mp4", apple))
        assertNull(capsuleMotionSafeUrl("https://mvod.itunes.apple.com.evil.net/x.mp4", apple))
        assertNull(capsuleMotionSafeUrl("https://127.0.0.1/secret", apple))
        assertNull(capsuleMotionSafeUrl("file:///etc/passwd", spotify))
        assertNull(capsuleMotionSafeUrl("https://canvaz.scdn.co.evil.net/v.mp4", spotify))
        assertNull(capsuleParseAppleMotion("""{"error":"No matching tracks found"}""", "Nights", "Frank Ocean"))
        assertNull(capsuleParseSpotifyMotion("""{"error":"No matching tracks found"}"""))
    }
}
