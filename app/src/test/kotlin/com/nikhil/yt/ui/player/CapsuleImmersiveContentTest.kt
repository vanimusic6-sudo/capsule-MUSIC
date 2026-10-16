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
    fun `the real upper artwork colour is never washed out`() {
        for (colour in listOf(Color.White, Color(0xFFFAFAFA), Color(0xFFFFEACC),
            Color(0xFFE42535), Color(0xFF2288DD), Color.Black)) {
            for (boundary in listOf(0.35f, 0.55f, 0.72f)) {
                assertEquals(colour, immersiveSeamColor(colour, Color(0xFF222222)))
                assertEquals(colour, immersiveStagedFloorColor(colour, 0f, boundary))
                assertEquals(colour, immersiveStagedFloorColor(colour, boundary * 0.42f, boundary))
                assertEquals(immersiveDeepTone(colour),
                    immersiveStagedFloorColor(colour, boundary, boundary))
                assertEquals(immersiveDeepTone(colour),
                    immersiveStagedFloorColor(colour, 1f, boundary))
            }
        }
    }

    @Test
    fun `page below album has a flat dark floor rather than a grey smoke layer`() {
        assertEquals(Color.White, ImmersiveControlInk)
        for (edge in listOf(Color.White, Color(0xFFF5F5F5),
            Color(0xFFBB1433), Color(0xFF0C1E4A), Color.Black)) {
            for (boundary in listOf(0.35f, 0.55f, 0.72f)) {
                val stops = immersiveStagedFloorStops(edge, boundary)
                assertEquals(34, stops.size)
                assertEquals(edge, stops.first().second)
                assertEquals(boundary, stops[32].first, 0.00001f)
                assertEquals(1f, stops.last().first, 0.00001f)
                assertEquals(stops[32].second, stops.last().second)
                val deep = immersiveDeepTone(edge)
                assertEquals(deep, stops.last().second)
                // The shader must be 100% opaque, and no gradient band is
                // allowed after the artwork has already ended.
                for (stop in stops) assertEquals(1f, stop.second.alpha, 0.00001f)
                for (frac in listOf(0.01f, 0.10f, 0.30f)) {
                    assertEquals(deep,
                        immersiveStagedFloorColor(edge, (boundary + frac).coerceAtMost(1f), boundary))
                }
            }
        }
    }

    @Test
    fun `dissolve is gradual and final pixel equals full-screen shader`() {
        val channelTolerance = 1f / 255f + 0.0001f
        for (edge in listOf(Color.White, Color(0xFFEEE8DC),
            Color(0xFFB82238), Color.Black)) {
            for (boundary in listOf(0.35f, 0.55f, 0.72f)) {
                for (texture in listOf(0f, 0.42f, 1f)) {
                    val fade = immersiveFadeProfile(texture, landscape = false)
                    val cover = immersiveStagedCoverFadeStops(edge, boundary, fade)
                    assertEquals(41, cover.size)
                    assertEquals(0f, cover.first().second.alpha, 0.00001f)
                    assertEquals(1f, cover.last().first, 0.00001f)
                    assertEquals(1f, cover.last().second.alpha, 0.00001f)
                    assertEquals(immersiveDeepTone(edge), cover.last().second)
                    var previousAlpha = 0f
                    for ((at, overlay) in cover) {
                        val expected = immersiveStagedFloorColor(edge, at * boundary, boundary)
                        assertEquals(expected.red, overlay.red, channelTolerance)
                        assertEquals(expected.green, overlay.green, channelTolerance)
                        assertEquals(expected.blue, overlay.blue, channelTolerance)
                        assertTrue(overlay.alpha + channelTolerance >= previousAlpha)
                        previousAlpha = overlay.alpha
                    }
                }
            }
        }
    }

    @Test
    fun `only the end of the album is faded, never the cover title`() {
        val calm = immersiveFadeProfile(bottomTexture = 0f, landscape = false)
        val busy = immersiveFadeProfile(bottomTexture = 1f, landscape = false)
        assertEquals(0.48f, calm.start, 0.00001f)
        assertEquals(0.40f, busy.start, 0.00001f)
        assertEquals(0f, immersiveCoverFadeAlpha(calm, 0.40f), 0.00001f)
        assertEquals(0f, immersiveCoverFadeAlpha(busy, 0.30f), 0.00001f)
        assertTrue(immersiveCoverFadeAlpha(calm, 0.70f) in 0.12f..0.40f)
        assertTrue(immersiveCoverFadeAlpha(calm, 0.90f) in 0.76f..0.99f)
        assertEquals(1f, immersiveCoverFadeAlpha(calm, 1f), 0.00001f)
        assertEquals(Color.White, ImmersiveControlInk)
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
