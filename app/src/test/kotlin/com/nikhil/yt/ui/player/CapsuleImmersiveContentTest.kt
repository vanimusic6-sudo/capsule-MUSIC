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
    fun `white and colourful covers keep their real sampled RGB and static white controls`() {
        assertEquals(Color.White, ImmersiveControlInk)
        for (edge in listOf(Color.White, Color(0xFFF3F3F3),
            Color(0xFFF3DECA), Color(0xFFCE1435), Color(0xFF1266D0), Color.Black)) {
            assertEquals(edge, immersiveSeamColor(edge, Color(0xFF242529)))
            val dark = immersiveDeepTone(edge)
            val tolerance = 1f / 255f + 0.0001f
            assertEquals(edge.red * 0.085f, dark.red, tolerance)
            assertEquals(edge.green * 0.085f, dark.green, tolerance)
            assertEquals(edge.blue * 0.085f, dark.blue, tolerance)
            assertEquals(Color.White, ImmersiveControlInk)
        }
    }

    @Test
    fun `single page gradient spans cover join without a plateau or strip`() {
        val tolerance = 1f / 255f + 0.0001f
        for (cover in listOf(0.35f, 0.48f, 0.55f, 0.69f)) {
            val extension = minOf(0.16f, (1f - cover) * 0.68f)
            for (edge in listOf(Color.White, Color(0xFFE3D9C9),
                Color(0xFFAA193D), Color.Black)) {
                val stops = immersiveContinuousPageStops(edge, cover, extension)
                assertEquals(49, stops.size)
                assertEquals(edge, stops.first().second)
                assertEquals(0f, stops.first().first, 0f)
                assertEquals(1f, stops.last().first, 0f)
                assertEquals(1f, stops.last().second.alpha, 0f)
                assertEquals(edge, immersiveContinuousPageColor(edge, 0.40f * cover, cover, extension))
                val end = cover + extension
                val fullDark = immersiveDeepTone(edge)
                assertEquals(fullDark, immersiveContinuousPageColor(edge, end, cover, extension))
                assertEquals(fullDark, immersiveContinuousPageColor(edge, 1f, cover, extension))
                var prev = edge
                for (stop in stops) {
                    val next = stop.second
                    assertTrue(next.red <= prev.red + tolerance)
                    assertTrue(next.green <= prev.green + tolerance)
                    assertTrue(next.blue <= prev.blue + tolerance)
                    prev = next
                }
                // The slope is smooth crossing the photo boundary. The real
                // colour is never reset by another independent floor shader.
                val step = 0.001f
                val left = immersiveContinuousPageColor(edge, cover - step, cover, extension)
                val mid = immersiveContinuousPageColor(edge, cover, cover, extension)
                val right = immersiveContinuousPageColor(edge, cover + step, cover, extension)
                assertTrue(kotlin.math.abs(
                    (mid.red - left.red) - (right.red - mid.red)
                ) < 0.015f)
            }
        }
    }

    @Test
    fun `photo stays untouched above its credits and reaches the page at its exact end`() {
        val tolerance = 1f / 255f + 0.0001f
        for (edge in listOf(Color.White, Color(0xFFEDE6DD),
            Color(0xFFD62942), Color.Black)) {
            for (cover in listOf(0.35f, 0.55f, 0.69f)) {
                val tail = minOf(0.15f, (1f - cover) * 0.68f)
                for (texture in listOf(0f, 0.5f, 1f)) {
                    val profile = immersiveFadeProfile(texture, landscape = false)
                    val overlay = immersiveContinuousCoverStops(edge, cover, tail, profile)
                    assertEquals(49, overlay.size)
                    assertEquals(0f, overlay.first().second.alpha, 0f)
                    assertEquals(1f, overlay.last().first, 0f)
                    assertEquals(1f, overlay.last().second.alpha, 0f)
                    assertEquals(
                        immersiveContinuousPageColor(edge, cover, cover, tail),
                        overlay.last().second,
                    )
                    assertEquals(0f, immersiveCoverFadeAlpha(profile, 0.55f), 0f)
                    var lastAlpha = 0f
                    for (stop in overlay) {
                        val expected = immersiveContinuousPageColor(
                            edge, stop.first * cover, cover, tail,
                        )
                        assertEquals(expected.red, stop.second.red, tolerance)
                        assertEquals(expected.green, stop.second.green, tolerance)
                        assertEquals(expected.blue, stop.second.blue, tolerance)
                        assertTrue("fade alpha cannot reverse", stop.second.alpha + tolerance >= lastAlpha)
                        lastAlpha = stop.second.alpha
                    }
                }
            }
        }
    }

    @Test
    fun `screenshot 153415 regression white title stays clean and controls become dark`() {
        // Concrete geometry of the supplied 712x1536 screenshot: physical
        // photo join near Y=800, with the first white metadata near Y=860.
        // The rejected implementations began a grey fog around Y=420 or
        // cut straight to black at Y=800. These anchors capture BOTH errors.
        val screen = 1536f
        val photo = 800f
        val cover = photo / screen
        val tail = 0.16f
        val profile = immersiveFadeProfile(0f, landscape = false)
        fun whiteLevel(y: Float): Float {
            val page = immersiveContinuousPageColor(Color.White, y / screen, cover, tail).red
            if (y >= photo) return page
            val alpha = immersiveCoverFadeAlpha(profile, y / photo)
            return 1f * (1f - alpha) + page * alpha
        }
        assertTrue("Do not lay a grey veil over the actual I GOT LOVE title",
            whiteLevel(540f) > 0.97f)
        assertTrue("Keep the artwork mostly bright into the lower details",
            whiteLevel(680f) > 0.76f)
        assertTrue("White title must be readable against the darkening floor",
            whiteLevel(860f) < 0.33f)
        assertTrue("Seekbar must no longer sit in full-screen grey fog",
            whiteLevel(1010f) < 0.14f)
        assertTrue("No sharp dark strip at the actual artwork join",
            kotlin.math.abs(whiteLevel(797f) - whiteLevel(803f)) < 0.035f)
    }

    @Test
    fun `artwork texture adjusts only the fade start and never control ink`() {
        val calm = immersiveFadeProfile(0f, landscape = false)
        val dense = immersiveFadeProfile(1f, landscape = false)
        assertEquals(0.68f, calm.start, 0.00001f)
        assertEquals(0.625f, dense.start, 0.00001f)
        assertTrue(dense.start < calm.start)
        assertTrue(immersiveFadeProfile(0.4f, landscape = true).start < calm.start)
        for (p in listOf(calm, dense)) {
            assertEquals(0f, immersiveCoverFadeAlpha(p, p.start), 0f)
            assertEquals(1f, immersiveCoverFadeAlpha(p, 1f), 0f)
            assertTrue(immersiveCoverFadeAlpha(p, 0.85f) in 0f..1f)
        }
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
