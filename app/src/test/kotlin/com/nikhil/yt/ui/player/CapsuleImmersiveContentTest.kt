package com.nikhil.yt.ui.player

import androidx.compose.ui.graphics.Color
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
    fun `VIDEO to AUDIO never exposes transparent album through track fade`() {
        // Recorded 9222.mp4: during closing, the thumbnail blended with the
        // live VIDEO because two independently driven alpha layers multiplied.
        for (trackFade in listOf(0f, 0.12f, 0.5f, 0.99f, 1f)) {
            assertEquals(
                1f,
                capsuleImmersiveArtworkOpacity(
                    frameMatchesTrack = true,
                    hasPresentedVideo = true,
                    trackFadeAlpha = trackFade,
                ),
                0.0001f,
            )
        }
        // The outgoing DIFFERENT song must still fade out on track switches.
        assertEquals(
            0.25f,
            capsuleImmersiveArtworkOpacity(
                frameMatchesTrack = false,
                hasPresentedVideo = true,
                trackFadeAlpha = 0.25f,
            ),
            0.0001f,
        )
        // Before any VIDEO has been presented, track-fade behaviour is intact.
        assertEquals(
            0.6f,
            capsuleImmersiveArtworkOpacity(
                frameMatchesTrack = true,
                hasPresentedVideo = false,
                trackFadeAlpha = 0.6f,
            ),
            0.0001f,
        )
    }

    @Test
    fun `invisible artwork preload ends immediately after first decoded image`() {
        val url = "https://i.ytimg.com/vi/ABC123def45/maxresdefault.jpg"
        assertTrue(capsuleImmersiveNeedsArtworkPreload(false, true, url))
        assertTrue(!capsuleImmersiveNeedsArtworkPreload(true, true, url))
        assertTrue(!capsuleImmersiveNeedsArtworkPreload(false, false, url))
        assertTrue(!capsuleImmersiveNeedsArtworkPreload(false, true, null))
    }

    @Test
    fun `status bar leaves before immersive player reaches the top`() {
        assertTrue(!immersiveStatusBarShouldHide(open = true, expansionProgress = 0f))
        assertTrue(
            !immersiveStatusBarShouldHide(
                open = true,
                expansionProgress = ImmersiveStatusBarHideProgress - 0.01f,
            ),
        )
        assertTrue(
            immersiveStatusBarShouldHide(
                open = true,
                expansionProgress = ImmersiveStatusBarHideProgress,
            ),
        )
        assertTrue(
            "the trigger must happen before the sheet reaches its expanded anchor",
            ImmersiveStatusBarHideProgress < 1f,
        )
    }

    @Test
    fun `collapsed or dismissed immersive player never owns the status bar`() {
        assertTrue(!immersiveStatusBarShouldHide(open = false, expansionProgress = 1f))
    }

    @Test
    fun `long staged gradient preserves colour and never makes hard tonal bands`() {
        assertEquals(Color.White, ImmersiveControlInk)
        for (edge in listOf(
            Color.White, Color(0xFFF5F5F5), Color(0xFFE5DECF),
            Color(0xFFD32254), Color(0xFF278DB5), Color.Black,
        )) {
            for (coverFraction in listOf(0.35f, 0.42f, 0.55f, 0.68f, 0.72f)) {
                val floor = immersiveStagedFloorStops(edge, coverFraction)
                assertEquals(49, floor.size)
                assertEquals(edge, floor.first().second)
                assertEquals(edge, immersiveStagedFloorColor(edge, 0f, coverFraction))
                assertEquals(1f, floor.last().first, 0.00001f)
                assertEquals(1f, floor.last().second.alpha, 0.00001f)
                val bottom = floor.last().second
                // Compose's ARGB quantization is one 8-bit channel, not a numerical
                // colour error: don't assert unrealistic 1e-4 colour precision.
                val precision = 1f / 255f + 0.0001f
                assertEquals(edge.red * 0.09f, bottom.red, precision)
                assertEquals(edge.green * 0.09f, bottom.green, precision)
                assertEquals(edge.blue * 0.09f, bottom.blue, precision)
                var previous = edge
                for (stop in floor) {
                    assertTrue(stop.second.red <= previous.red + precision)
                    assertTrue(stop.second.green <= previous.green + precision)
                    assertTrue(stop.second.blue <= previous.blue + precision)
                    assertEquals("page shader must be opaque", 1f, stop.second.alpha, 0.00001f)
                    previous = stop.second
                }
                // Every adjacent tone is gentle. No single shader interval
                // may create a visible grey bar on a white/bright album cover.
                for (index in 1 until floor.size) {
                    val a = floor[index - 1].second
                    val b = floor[index].second
                    assertTrue("tonal band too sharp at stop $index",
                        kotlin.math.abs(b.red - a.red) < 0.075f &&
                            kotlin.math.abs(b.green - a.green) < 0.075f &&
                            kotlin.math.abs(b.blue - a.blue) < 0.075f)
                }
            }
        }
    }

    @Test
    fun `image boundary always has one smooth filmic tone on tall and short viewports`() {
        val coverFractions = listOf(0.35f, 0.41f, 0.55f, 0.64f, 0.72f)
        for (boundary in coverFractions) {
            assertEquals(0f, immersiveGradientPosition(0f, boundary), 0.00001f)
            assertEquals(1f, immersiveGradientPosition(1f, boundary), 0.00001f)
            assertEquals(0.55f, immersiveGradientPosition(boundary, boundary), 0.00001f)
            val atJoin = immersiveStagedFloorColor(Color.White, boundary, boundary)
            assertTrue(atJoin.red in 0.41f..0.44f)
            val epsilon = 0.001f
            val below = immersiveGradientPosition(boundary - epsilon, boundary)
            val at = immersiveGradientPosition(boundary, boundary)
            val above = immersiveGradientPosition(boundary + epsilon, boundary)
            // The reparameterization has a continuous derivative at the cover
            // join, unlike an independent stretch for the photo and page.
            assertEquals(at - below, above - at, 0.00002f)
            var lastPosition = 0f
            for (index in 1..100) {
                val position = index / 100f
                val mapped = immersiveGradientPosition(position, boundary)
                assertTrue("no overshoot or reverse gradient travel", mapped >= lastPosition)
                lastPosition = mapped
            }
        }
    }

    @Test
    fun `white and pastel art flow softly to fixed white text on deeper controls`() {
        assertEquals(Color.White, ImmersiveControlInk)
        val atTitle = immersiveStagedFloorColor(Color.White, 0.60f)
        val atControls = immersiveStagedFloorColor(Color.White, 0.80f)
        assertTrue("title must have dark enough backdrop", atTitle.red < 0.36f)
        assertTrue("controls must sit on a deep tone", atControls.red < 0.16f)
        assertTrue("white artwork must never become tinted",
            kotlin.math.abs(atTitle.red - atTitle.blue) < 0.0001f)
        assertTrue("never recolour extracted near-white to fixed graphite",
            immersiveStagedFloorColor(Color(0xFFF5F5F5), 0f) == Color(0xFFF5F5F5))
        assertEquals(Color.White, ImmersiveControlInk)
    }

    @Test
    fun `photo fade and background colour match at every sampled pixel`() {
        val precision = 1f / 255f + 0.0001f
        for (edge in listOf(Color.White, Color(0xFFE5DECF), Color(0xFF9F1530), Color.Black)) {
            for (coverFraction in listOf(0.35f, 0.55f, 0.68f)) {
                for (texture in listOf(0f, 0.6f, 1f)) {
                    val profile = immersiveFadeProfile(texture, landscape = false)
                    val stops = immersiveStagedCoverFadeStops(edge, coverFraction, profile)
                    assertEquals(65, stops.size)
                    assertEquals(0f, stops.first().first, 0.00001f)
                    assertEquals(0f, stops.first().second.alpha, 0.00001f)
                    assertEquals(1f, stops.last().first, 0.00001f)
                    assertEquals(1f, stops.last().second.alpha, 0.00001f)
                    assertEquals(immersiveStagedFloorColor(edge, coverFraction, coverFraction),
                        stops.last().second)
                    var alpha = 0f
                    for (stop in stops) {
                        assertTrue("photo alpha must never jump backwards",
                            stop.second.alpha + precision >= alpha)
                        alpha = stop.second.alpha
                        val matchingY = stop.first * coverFraction
                        val page = immersiveStagedFloorColor(edge, matchingY, coverFraction)
                        assertEquals(page.red, stop.second.red, precision)
                        assertEquals(page.green, stop.second.green, precision)
                        assertEquals(page.blue, stop.second.blue, precision)
                    }
                }
            }
        }
    }

    @Test
    fun `busy art gets an earlier but still gradual cover fade`() {
        val calm = immersiveFadeProfile(bottomTexture = 0f, landscape = false)
        val medium = immersiveFadeProfile(bottomTexture = 0.35f, landscape = false)
        val hard = immersiveFadeProfile(bottomTexture = 1f, landscape = false)
        assertEquals(0.28f, calm.start, 0.00001f)
        assertEquals(0.48f, calm.firstStop, 0.00001f)
        assertEquals(0.13f, calm.firstAlpha, 0.00001f)
        assertEquals(0.72f, calm.secondStop, 0.00001f)
        assertEquals(0.64f, calm.secondAlpha, 0.00001f)
        assertEquals(0.90f, calm.thirdStop, 0.00001f)
        assertEquals(0.92f, calm.thirdAlpha, 0.00001f)
        assertEquals(calm, medium)
        assertEquals(0.16f, hard.start, 0.00001f)
        assertEquals(0.40f, hard.firstStop, 0.00001f)
        assertEquals(0.22f, hard.firstAlpha, 0.00001f)
        assertEquals(0.68f, hard.secondStop, 0.00001f)
        assertEquals(0.76f, hard.secondAlpha, 0.00001f)
        assertEquals(0.90f, hard.thirdStop, 0.00001f)
        assertEquals(0.97f, hard.thirdAlpha, 0.00001f)
        for (position in listOf(0f, 0.12f, 0.25f, 0.45f, 0.55f, 0.72f, 0.9f, 1f)) {
            assertTrue(immersiveCoverFadeAlpha(calm, position) in 0f..1f)
            assertTrue(immersiveCoverFadeAlpha(hard, position) in 0f..1f)
        }
    }

    @org.junit.Test
    fun editorWorkspaceStopsAtArtworkAndQueueRail() {
        val total = 851.dp
        val artwork = immersiveArtworkHeight(total)
        val workspace =
            immersiveEditorHeight(
                totalHeight = total,
                artworkHeight = artwork,
                bottomPadding = 0.dp,
            )

        org.junit.Assert.assertEquals(319.45f, workspace.value, 0.01f)

        // Very short windows clamp instead of producing a negative canvas.
        org.junit.Assert.assertEquals(
            0.dp,
            immersiveEditorHeight(
                totalHeight = 240.dp,
                artworkHeight = 200.dp,
                bottomPadding = 0.dp,
            ),
        )
    }

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
