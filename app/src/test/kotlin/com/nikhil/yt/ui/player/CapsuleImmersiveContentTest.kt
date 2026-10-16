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
    fun `stage starts at true album colour and produces deep same-hue floor`() {
        assertEquals(Color.White, ImmersiveControlInk)
        for (edge in listOf(
            Color.White, Color(0.96f, 0.96f, 0.96f, 1f),
            Color(0.85f, 0.13f, 0.24f, 1f), Color(0.12f, 0.62f, 0.84f, 1f),
            Color.Black,
        )) {
            val floor = immersiveStagedFloorStops(edge)
            assertEquals(33, floor.size)
            assertEquals(edge, floor.first().second)
            assertEquals(edge, immersiveStagedFloorColor(edge, 0f))
            assertEquals(edge, immersiveStagedFloorColor(edge, 0.20f))
            assertEquals(edge, immersiveSeamColor(edge, Color(0xFF242529)))
            assertEquals(1f, floor.last().first, 0.00001f)
            assertEquals(1f, floor.last().second.alpha, 0.00001f)
            var prior = edge
            for (stop in floor) {
                val current = stop.second
                assertTrue(current.red <= prior.red + 0.00001f)
                assertTrue(current.green <= prior.green + 0.00001f)
                assertTrue(current.blue <= prior.blue + 0.00001f)
                prior = current
            }
            val bottom = floor.last().second
            assertEquals(edge.red * 0.12f, bottom.red, 0.0001f)
            assertEquals(edge.green * 0.12f, bottom.green, 0.0001f)
            assertEquals(edge.blue * 0.12f, bottom.blue, 0.0001f)
        }
    }

    @Test
    fun `white art is already dark behind fixed white metadata and controls`() {
        assertEquals(Color.White, ImmersiveControlInk)
        val underTitle = immersiveStagedFloorColor(Color.White, 0.60f)
        val lower = immersiveStagedFloorColor(Color.White, 0.80f)
        assertTrue("title should not sit on overexposed white", underTitle.red < 0.35f)
        assertTrue("lower controls should sit over deep grey", lower.red < 0.20f)
        assertTrue("no accidental tint on neutral cover",
            kotlin.math.abs(underTitle.red - underTitle.green) < 0.0001f)
        assertTrue("no accidental tint on neutral cover",
            kotlin.math.abs(underTitle.green - underTitle.blue) < 0.0001f)
    }

    @Test
    fun `full-screen tonal shading and fading album join with exact RGB`() {
        for (edge in listOf(Color.White, Color(0xFFE5DECF), Color(0xFF9F1530), Color.Black)) {
            for (coverFraction in listOf(0.39f, 0.55f, 0.68f)) {
                for (texture in listOf(0f, 0.6f, 1f)) {
                    val fade = immersiveFadeProfile(texture, landscape = false)
                    val coverStops = immersiveStagedCoverFadeStops(edge, coverFraction, fade)
                    assertEquals(49, coverStops.size)
                    assertEquals(0f, coverStops.first().first, 0.00001f)
                    assertEquals(0f, coverStops.first().second.alpha, 0.00001f)
                    val last = coverStops.last()
                    assertEquals(1f, last.first, 0.00001f)
                    assertEquals(1f, last.second.alpha, 0.00001f)
                    assertEquals(immersiveStagedFloorColor(edge, coverFraction), last.second)
                    var priorAlpha = 0f
                    for (stop in coverStops) {
                        assertTrue("cover alpha must never reverse", stop.second.alpha + 0.0001f >= priorAlpha)
                        priorAlpha = stop.second.alpha
                        val correspondingPageTone =
                            immersiveStagedFloorColor(edge, stop.first * coverFraction)
                        assertEquals(correspondingPageTone.red, stop.second.red, 0.0001f)
                        assertEquals(correspondingPageTone.green, stop.second.green, 0.0001f)
                        assertEquals(correspondingPageTone.blue, stop.second.blue, 0.0001f)
                    }
                }
            }
        }
    }

    @Test
    fun `difficult artwork only boosts fade after threshold`() {
        val calm = immersiveFadeProfile(bottomTexture = 0f, landscape = false)
        val medium = immersiveFadeProfile(bottomTexture = 0.35f, landscape = false)
        val hard = immersiveFadeProfile(bottomTexture = 1f, landscape = false)

        // Keep the current calm profile exactly as-is.
        assertEquals(0.42f, calm.start, 0.0001f)
        assertEquals(0.60f, calm.firstStop, 0.0001f)
        assertEquals(0.15f, calm.firstAlpha, 0.0001f)
        assertEquals(0.74f, calm.secondStop, 0.0001f)
        assertEquals(0.46f, calm.secondAlpha, 0.0001f)
        assertEquals(0.89f, calm.thirdStop, 0.0001f)
        assertEquals(0.80f, calm.thirdAlpha, 0.0001f)

        // Medium-detail artwork below the threshold must not be flattened by the adaptive boost.
        assertEquals(calm.start, medium.start, 0.0001f)
        assertEquals(calm.firstAlpha, medium.firstAlpha, 0.0001f)
        assertEquals(calm.secondAlpha, medium.secondAlpha, 0.0001f)
        assertEquals(calm.thirdAlpha, medium.thirdAlpha, 0.0001f)

        // Fully difficult artwork reaches the known-good hard endpoint from a9ef7ff.
        assertEquals(0.11f, hard.start, 0.0001f)
        assertEquals(0.39f, hard.firstStop, 0.0001f)
        assertEquals(0.50f, hard.firstAlpha, 0.0001f)
        assertEquals(0.56f, hard.secondStop, 0.0001f)
        assertEquals(0.90f, hard.secondAlpha, 0.0001f)
        assertEquals(0.79f, hard.thirdStop, 0.0001f)
        assertEquals(1f, hard.thirdAlpha, 0.0001f)
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
