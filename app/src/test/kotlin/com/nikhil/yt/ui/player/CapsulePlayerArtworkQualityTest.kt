package com.nikhil.yt.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsulePlayerArtworkQualityTest {
    private val id = "abcdefghijk"

    @Test fun hqdefaultIsNotAFullScreenPosterOrFallback() {
        val tiny = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                "https://i.ytimg.com/vi/$id/hq720.jpg",
            ),
            capsuleFullPlayerArtworkCandidates(tiny),
        )
    }

    @Test fun knownGoogleMusicCoverRequests960Pixels() {
        assertEquals(
            listOf("https://lh3.googleusercontent.com/album=w960-h960-l90-rj"),
            capsuleFullPlayerArtworkCandidates(
                "https://lh3.googleusercontent.com/album=w120-h120-l90-rj",
            ),
        )
    }

    @Test fun noUnsafeRewritesOfSignedOrThirdPartyImages() {
        val signed = "https://i.ytimg.com/vi/$id/hqdefault.jpg?signature=abc"
        assertEquals(listOf(signed), capsuleFullPlayerArtworkCandidates(signed))
        val unknown = "https://example.net/cover=w120-h120"
        assertEquals(listOf(unknown), capsuleFullPlayerArtworkCandidates(unknown))
        assertEquals(emptyList<String>(), capsuleFullPlayerArtworkCandidates(null))
    }

    @Test fun rejectMisleadingYoutube200PlaceholderWithoutShowingItAsFullCover() {
        val maxres = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
        assertFalse(capsulePlayerPosterLargeEnough(maxres, 120, 90))
        assertFalse(capsulePlayerPosterLargeEnough(maxres, 640, 360))
        assertTrue(capsulePlayerPosterLargeEnough(maxres, 1280, 720))
        assertTrue(capsulePlayerPosterLargeEnough(
            "https://lh3.googleusercontent.com/id=w960-h960", 960, 960))
    }
}
