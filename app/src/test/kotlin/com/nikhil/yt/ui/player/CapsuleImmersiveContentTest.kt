package com.nikhil.yt.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounds are read from the function rather than repeated here, so tuning the cover's share of
 * the screen does not break tests that are really about the clamp holding at all.
 */
private val Floor: Dp = immersiveArtworkHeight(0.dp)
private val Ceiling: Dp = immersiveArtworkHeight(100_000.dp)

class CapsuleImmersiveContentTest {
    @Test
    fun `rapid skips keep one outgoing fade active instead of restarting per incoming track`() {
        val originalFrame = "track-A|cover-A"
        // Once B has been selected, A must start leaving before B has decoded anything.
        assertTrue(shouldFadeOutImmersiveFrame(originalFrame, "track-B|cover-B", visible = true))
        // Further rapid changes B -> C -> D do not alter the boolean animation key.
        assertTrue(shouldFadeOutImmersiveFrame(originalFrame, "track-C|cover-C", visible = true))
        assertTrue(shouldFadeOutImmersiveFrame(originalFrame, "track-D|cover-D", visible = true))
        // After A has fully left there is no outgoing frame left to restart.
        assertTrue(!shouldFadeOutImmersiveFrame(null, "track-D|cover-D", visible = true))
    }

    @Test
    fun `already selected song does not fade out while its thumbnail is still decoding`() {
        assertTrue(!shouldFadeOutImmersiveFrame("selected", "selected", visible = true))
        assertTrue(shouldFadeOutImmersiveFrame("selected", "selected", visible = false))
    }

    @Test
    fun `raw widescreen thumbnail stays neutral until its geometry and gradient are prepared`() {
        val raw = ImmersiveArtworkTone(displayUrl = null, landscape = false, ready = false)
        assertTrue(!canRevealImmersiveArtwork(raw, imageLoaded = false))
        assertTrue(!canRevealImmersiveArtwork(raw, imageLoaded = true))
        val stillUnprepared = raw.copy(
            displayUrl = "https://i.ytimg.com/vi/ABC123def45/hq720.jpg",
            landscape = true,
        )
        assertTrue(!canRevealImmersiveArtwork(stillUnprepared, imageLoaded = true))
    }

    @Test
    fun `sampled colour and final full image must both be ready before shared reveal`() {
        val tone = ImmersiveArtworkTone(
            landscape = true,
            displayUrl = "https://i.ytimg.com/vi/ABC123def45/maxresdefault.jpg",
            ready = true,
        )
        assertTrue(!canRevealImmersiveArtwork(tone, imageLoaded = false))
        assertTrue(canRevealImmersiveArtwork(tone, imageLoaded = true))
        assertTrue(!canRevealImmersiveArtwork(tone.copy(displayUrl = null), imageLoaded = true))
    }

    @Test
    fun `video thumbnail tries full frame qualities before an existing cropped cover`() {
        val id = "ABC123def45"
        val original = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val choices = immersiveArtworkCandidates(id, original)
        assertTrue(choices.first().endsWith("/maxresdefault.jpg"))
        assertTrue(choices.any { it.endsWith("/hq720.jpg") })
        assertTrue(choices.any { it.endsWith("/hqdefault.jpg") })
        assertEquals(choices.size, choices.distinct().size)
    }

    @Test
    fun `webp video thumbnails can select a full frame jpeg fallback`() {
        val id = "ABC123def45"
        val url = "https://i.ytimg.com/vi_webp/$id/hqdefault.webp"
        assertTrue(immersiveArtworkCandidates(id, url).first().endsWith("/maxresdefault.jpg"))
    }

    @Test
    fun `ordinary album art never starts extra YouTube thumbnail requests`() {
        assertEquals(1, immersiveArtworkCandidates("ABC123def45", "https://lh3.googleusercontent.com/test").size)
        assertTrue(immersiveArtworkCandidates(null, null).isEmpty())
    }

    @Test
    fun `an ordinary phone gives the cover about half the sheet`() {
        val height = immersiveArtworkHeight(800.dp)

        assertTrue(
            "800dp phone gave $height, which is not about half of it",
            height > 320.dp && height < 480.dp,
        )
    }

    @Test
    fun `a short window keeps the controls their room`() {
        assertEquals(
            "a landscape phone must not be handed a cover that squeezes the panel out",
            Floor,
            immersiveArtworkHeight(300.dp),
        )
        assertTrue("the floor itself has to be a cover, not a stripe", Floor >= 160.dp)
    }

    @Test
    fun `a tall window does not hand over an unbounded cover`() {
        assertEquals(Ceiling, immersiveArtworkHeight(2000.dp))
        assertTrue("the ceiling has to leave the controls a screen", Ceiling <= 560.dp)
    }

    @Test
    fun `no window size produces a height a layout could read as negative`() {
        for (available in listOf(0, 1, 120, 300, 480, 800, 1600, 4000)) {
            val height = immersiveArtworkHeight(available.dp)
            assertTrue("$available dp gave $height", height in Floor..Ceiling)
        }
    }

    @Test
    fun `a taller window never gives a shorter cover`() {
        var previous = immersiveArtworkHeight(0.dp)
        for (available in listOf(100, 200, 400, 600, 800, 1200, 2400)) {
            val height = immersiveArtworkHeight(available.dp)
            assertTrue("$available dp shrank the cover to $height from $previous", height >= previous)
            previous = height
        }
    }
}
