package com.nikhil.yt.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkResolutionTest {
    @Test fun upgradesOnlyKnownTinyPublicImageSources() {
        assertEquals(
            "https://lh3.googleusercontent.com/a=b=w512-h512-l90-rj",
            capsuleArtworkUrl("https://lh3.googleusercontent.com/a=b=w120-h120-l90-rj"),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/id=s960-c",
            capsuleArtworkUrl("https://lh3.googleusercontent.com/id=s100-c", 960),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/id=w1024-h1024",
            capsuleArtworkUrl("https://lh3.googleusercontent.com/id=w1024-h1024"),
        )
    }

    @Test fun artworkCandidatesPrioritizeOriginalAndTrackFallbacksWithoutDuplicates() {
        val primary = "https://lh3.googleusercontent.com/album=w120-h120"
        val songA = "https://yt3.ggpht.com/song=s120"
        val songB = "https://cdn.example.org/songB.jpg"
        assertEquals(
            listOf(
                "https://lh3.googleusercontent.com/album=w960-h960",
                primary,
                "https://yt3.ggpht.com/song=s960",
                songA,
                songB,
            ),
            detailArtworkCandidates(primary, fallbackThumbnailUrl = songA,
                fallbackThumbnailUrls = listOf(songA, songB, "", songB)),
        )
    }

    @Test fun missingAlbumCoverCanFallBackToSongAndEmptyCollectionsStayEmpty() {
        assertEquals(
            listOf("https://cdn.example.org/track.png"),
            detailArtworkCandidates(null,
                fallbackThumbnailUrls = listOf("", "https://cdn.example.org/track.png")),
        )
        assertEquals(emptyList<String>(), detailArtworkCandidates(null))
    }

    @Test fun doesNotRecreateSignedOrThirdPartyUrls() {
        val signed = "https://lh3.googleusercontent.com/id=w120-h120?signature=private"
        assertEquals(signed, capsuleArtworkUrl(signed))
        val external = "https://cdn.audius.co/image=s120"
        assertEquals(external, capsuleArtworkUrl(external))
        assertEquals(null, capsuleArtworkUrl(null))
    }
}
