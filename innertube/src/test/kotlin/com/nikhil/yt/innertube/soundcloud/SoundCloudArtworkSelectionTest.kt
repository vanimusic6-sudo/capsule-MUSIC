package com.nikhil.yt.innertube.soundcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.Image

class SoundCloudArtworkSelectionTest {
    @Test fun selectsLargestAvailableImageEvenWhenTinyThumbnailComesFirst() {
        val thumbnails = listOf(
            Image("https://i1.sndcdn.com/artworks-demo-mini.jpg", 16, 16, Image.ResolutionLevel.LOW),
            Image("https://i1.sndcdn.com/artworks-demo-large.jpg", 100, 100, Image.ResolutionLevel.LOW),
            Image("https://i1.sndcdn.com/artworks-demo-t500x500.jpg", 500, 500, Image.ResolutionLevel.MEDIUM),
        )
        assertEquals(
            "https://i1.sndcdn.com/artworks-demo-t500x500.jpg",
            SoundCloudNewPipe.bestSoundCloudArtwork(thumbnails),
        )
    }

    @Test fun unknownHttpsArtworkIsPreservedAndInsecureArtworkIsRejected() {
        assertEquals(
            "https://example.com/cover.jpg",
            SoundCloudNewPipe.bestSoundCloudArtwork(
                listOf(Image("https://example.com/cover.jpg", 400, 400, Image.ResolutionLevel.MEDIUM)),
            ),
        )
        assertNull(
            SoundCloudNewPipe.bestSoundCloudArtwork(
                listOf(Image("http://i1.sndcdn.com/cover-large.jpg", 100, 100, Image.ResolutionLevel.LOW)),
            ),
        )
    }
}
