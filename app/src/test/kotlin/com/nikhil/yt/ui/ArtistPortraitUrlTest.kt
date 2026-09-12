package com.nikhil.yt.ui

import com.nikhil.yt.ui.utils.artistPortraitUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistPortraitUrlTest {
    @Test fun removesSquareAndFaceCropFlagsFromGoogleArtwork() {
        assertEquals("https://lh3.googleusercontent.com/portrait=s1600",
            "https://lh3.googleusercontent.com/portrait=w1200-h1200-p-l90-rj".artistPortraitUrl())
        assertEquals("https://yt3.ggpht.com/avatar=s1600",
            "https://yt3.ggpht.com/avatar=s120-c".artistPortraitUrl())
        assertEquals("https://yt3.googleusercontent.com/avatar=s800",
            "https://yt3.googleusercontent.com/avatar=w400-h400-c".artistPortraitUrl(800))
    }

    @Test fun preservesOtherHostsAndSignedImageRequests() {
        for (url in listOf("https://example.com/photo?w=100&h=100",
            "https://lh3.googleusercontent.com/photo=w100-h100?signature=original",
            "content://local/portrait")) {
            assertEquals(url, url.artistPortraitUrl())
        }
    }
}
