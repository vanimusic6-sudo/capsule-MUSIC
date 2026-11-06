package com.nikhil.yt.ui.component

import androidx.compose.runtime.mutableStateOf
import com.nikhil.yt.ui.screens.albumHasHeroHeader
import com.nikhil.yt.viewmodels.AlbumUiState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailToolbarRevealTest {
    private fun fade(index: Int, offset: Int, bottom: Float = 600f, bar: Float = 100f) =
        detailArtworkFadeLikeArtist(index, offset, bottom, bar)

    @Test fun springHandoffVelocityNeverKicksOrReversesAtShortDistances() {
        assertEquals(0f, detailHeaderDockVelocity(15f, -850f), .0001f)
        assertEquals(0f, detailHeaderDockVelocity(-15f, 850f), .0001f)
        assertEquals(40.5f, detailHeaderDockVelocity(15f, 850f), .0001f)
        assertEquals(-40.5f, detailHeaderDockVelocity(-15f, -850f), .0001f)
        assertEquals(850f, detailHeaderDockVelocity(500f, 1_500f), .0001f)
        assertEquals(-850f, detailHeaderDockVelocity(-500f, -1_500f), .0001f)
        assertEquals(50f, detailHeaderDockVelocity(500f, 50f), .0001f)
        assertEquals(0f, detailHeaderDockVelocity(Float.NaN, 100f), .0001f)
        assertEquals(0f, detailHeaderDockVelocity(200f, Float.NaN), .0001f)
        assertEquals(0f, detailHeaderDockVelocity(0f, 900f), .0001f)
        // Approaching an anchor, the permitted launch speed must continually
        // decrease, preventing the end-of-transition jerk in the recording.
        val allowed = listOf(200f, 100f, 50f, 20f, 5f).map {
            detailHeaderDockVelocity(it, 2_000f)
        }
        assertTrue(allowed.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test fun swipeFromSongsReachesTheHeroDuringNativeDecayWithoutADeadZone() {
        val distance = 500f
        // Same position, opposite finger direction: different endpoint.
        assertEquals(499, detailHeaderSnapTargetPx(95, distance, 1f, 2800f))
        assertEquals(0, detailHeaderSnapTargetPx(450, distance, 1f, -2800f))
        // Initially outside the hero: normal scroll can continue. As soon
        // as decay reaches the partially-open header, snapping is defined.
        assertEquals(null, detailHeaderSnapTargetPx(510, distance, 1f, -1500f))
        assertEquals(0, detailHeaderSnapTargetPx(395, distance, 1f, -850f))
        assertEquals(499, detailHeaderSnapTargetPx(85, distance, 1f, 850f))
    }

    @Test fun veryFastRowGestureStillHasDeterministicMagnetEndpoint() {
        assertEquals(499, detailHeaderSnapTargetPx(300, 500f, 1f, 8_500f))
        assertEquals(0, detailHeaderSnapTargetPx(300, 500f, 1f, -8_500f))
        assertEquals(0, detailHeaderSnapTargetPx(30, 500f, 1f, 0f))
        assertEquals(499, detailHeaderSnapTargetPx(450, 500f, 1f, 0f))
        assertEquals(null, detailHeaderSnapTargetPx(600, 500f, 1f, 8_500f))
    }

    @Test fun titleFollowsMeasuredDistanceInsteadOfEasedPhotoAlpha() {
        assertEquals(0f, detailTitleTravelFraction(0, 0, 601f, 100f), 0f)
        assertEquals(.20f, detailTitleTravelFraction(0, 100, 601f, 100f), .0001f)
        assertEquals(.75f, detailTitleTravelFraction(0, 375, 601f, 100f), .0001f)
        assertEquals(1f, detailTitleTravelFraction(0, 500, 601f, 100f), 0f)
        assertEquals(1f, detailTitleTravelFraction(1, 0, 601f, 100f), 0f)
        assertEquals(1f, detailTitleTravelFraction(0, 0, Float.NaN, 100f, true), 0f)
        assertEquals(0f, detailTitleTravelFraction(0, 100, Float.NaN, 100f), 0f)

        assertEquals(0f, detailHeaderHairlineFraction(.94f), 0f)
        assertEquals(.5f, detailHeaderHairlineFraction(.975f), .0001f)
        assertEquals(1f, detailHeaderHairlineFraction(1f), 0f)
        val samples = (0..100).map { detailHeaderHairlineFraction(it / 100f) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun initialHeaderPresenceNeverDependsOnImageCallbacks() {
        val motion = DetailHeaderMotionState(LazyListState(), Density(1f))
        assertEquals(1f, motion.artworkAlpha(), 0f)
        assertEquals(0f, motion.toolbarTitleAlpha(), 0f)
        assertEquals(0f, detailToolbarBackdropAlpha(motion.artworkFade()), 0f)
        val presence = mutableStateOf(false)
        val empty = DetailHeaderMotionState(LazyListState(), Density(1f), presence)
        assertEquals(1f, empty.toolbarTitleAlpha(), 0f)
        presence.value = true
        assertEquals(1f, empty.artworkAlpha(), 0f)
        assertEquals(0f, empty.toolbarTitleAlpha(), 0f)
    }

    @Test fun titleFlightJoinsBothAnchorsWithContinuousVelocity() {
        assertEquals(0f, detailTitleDockProgress(0f), 0f)
        assertEquals(1f, detailTitleDockProgress(1f), 0f)
        assertEquals(0f, detailTitleDockProgress(Float.NaN), 0f)
        val points = (0..1000).map { detailTitleDockProgress(it / 1000f) }
        assertTrue(points.zipWithNext().all { (a, b) -> b >= a && b - a <= .0012f })
        val step = points.zipWithNext().map { (a, b) -> b - a }
        assertTrue("The first pixel must respond to the finger", step.first() > .0007f)
        assertTrue("Docking must lose velocity gracefully",
            step.last() < step[500] * .1f)
        assertTrue("The title should remain responsive in the middle",
            step[500] > .001f)
        // No velocity jump where either endpoint easing joins the
        // near-linear interior: derivative changes must be gradual.
        for (edge in listOf(60, 930)) {
            assertTrue(kotlin.math.abs(step[edge] - step[edge-1]) < .00001f)
        }
    }

    @Test fun titleFlightDoesNotCountScrollDistanceTwice() {
        val source = androidx.compose.ui.geometry.Offset(180f, 480f)
        val target = androidx.compose.ui.geometry.Rect(52f, 28f, 298f, 60f)
        val baseline = 22f
        fun geometry(p: Float, offset: Float) = detailTitleGeometry(
            progress = p, compactScale = .88f, width = 300f, height = 58f,
            lineLeft = 4f, baseline = baseline, lineHeight = 29f,
            sourceCenter = source, scrollOffset = offset, target = target,
        )
        for (step in 0..20) {
            val p = step / 20f
            val a = geometry(p, p * 600f)
            val b = geometry(p, 0f)
            // Even while the LazyColumn moves, the source is a stored
            // unscrolled anchor. Only p may affect the paragraph's flight.
            assertEquals(b.origin.y, a.origin.y, .001f)
            assertEquals(b.clip.top, a.clip.top, .001f)
        }

        val y = (0..4).map { i ->
            val g = geometry(i / 4f, (i / 4f) * 600f)
            g.origin.y + baseline * g.scale
        }
        val increments = y.zipWithNext().map { (a, b) -> b - a }
        assertTrue("Title baseline must move monotonically",
            increments.all { it < 0f })
        assertTrue("Middle remains close to linear, without a sudden boost",
            kotlin.math.abs(increments[1] - increments[2]) < 1f)
        // The last quarter includes a deliberate short ease-out. Previously
        // the moving title retained full speed right until it hit the bar.
        val dense = (0..100).map { i ->
            val g = geometry(i / 100f, i * 6f)
            g.origin.y + baseline * g.scale
        }
        val perFrame = dense.zipWithNext().map { (a,b) -> kotlin.math.abs(b-a) }
        assertTrue("Start without a sudden full-speed jump",
            perFrame.first() < perFrame[40])
        assertTrue("Finish without a full-speed wall impact",
            perFrame.last() < perFrame[40] * .1f)
    }

    @Test fun oneParagraphMovesToItsBaselineWithoutChangingGlyphSpacing() {
        val target = androidx.compose.ui.geometry.Rect(56f, 25f, 300f, 54f)
        for (height in listOf(29f, 58f)) {
            val source = androidx.compose.ui.geometry.Offset(180f, 400f)
            val start = detailTitleGeometry(0f, .88f, 300f, height, 5f, 22f, 29f, source, 0f, target)
            assertEquals(30f, start.origin.x, .001f)
            assertEquals(1f, start.scale, 0f)
            val end = detailTitleGeometry(1f, .88f, 300f, height, 5f, 22f, 29f, source, 300f, target)
            assertEquals(56f, end.origin.x + 5f * end.scale, .001f)
            assertEquals(target, end.clip)
            assertEquals(.88f, end.scale, 0f)
            for (i in 0..100) {
                val g = detailTitleGeometry(i / 100f, .88f, 300f, height, 5f, 22f, 29f, source, 100f, target)
                assertEquals("Every glyph uses the same uniform scale", 80f * g.scale,
                    (g.origin.x + 85f * g.scale) - (g.origin.x + 5f * g.scale), .0001f)
            }
        }
    }

    @Test fun longTitleClippingBecomesContinuousFeatherNotAHardCut() {
        val width = 30f
        assertEquals(0f, detailTitleFeatherStrength(-2f, width), 0f)
        assertEquals(0f, detailTitleFeatherStrength(0f, width), 0f)
        assertEquals(0.5f, detailTitleFeatherStrength(15f, width), .00001f)
        assertEquals(1f, detailTitleFeatherStrength(30f, width), 0f)
        assertEquals(1f, detailTitleFeatherStrength(200f, width), 0f)

        val samples = (0..300).map { detailTitleFeatherStrength(it / 10f, width) }
        assertTrue("Opacity mask must never jump, even at the first trimmed pixel",
            samples.zipWithNext().all { (a, b) -> b >= a && b - a < 0.01f })
        assertEquals("Reversing scroll must not retain a stale mask",
            detailTitleFeatherStrength(12f, width),
            detailTitleFeatherStrength(12f, width), 0f)
    }

    @Test fun noOverlapMustKeepOriginalTitleFullyOpaque() {
        assertEquals(0f, detailTitleFeatherStrength(0f, 1f), 0f)
        assertEquals(0f, detailTitleFeatherStrength(Float.NaN, 30f), 0f)
        assertEquals(0f, detailTitleFeatherStrength(Float.NEGATIVE_INFINITY, 30f), 0f)
    }

    @Test fun loadingPortraitIsAHeaderEvenBeforeSongsArrive() {
        assertTrue(albumHasHeroHeader(false, AlbumUiState.Loading))
        assertTrue(albumHasHeroHeader(false, AlbumUiState.Content))
        assertTrue(albumHasHeroHeader(true, AlbumUiState.Error()))
        assertEquals(false, albumHasHeroHeader(false, AlbumUiState.Empty))
        assertEquals(false, albumHasHeroHeader(false, AlbumUiState.Error()))
    }

    @Test fun fadeBeginsAtOldScrollPointButCompletesAtSameMeasuredToolbarEdge() {
        // Photo edge = 600px; bar bottom = 100px: 500px scroll travel.
        // Earlier start = 12% (60px), fixed end = 499px. We must not
        // move that terminal point or create a new solid-black interval.
        assertEquals(0f, fade(0, 0), 0f)
        assertEquals(0f, fade(0, 40), 0f)
        assertEquals(0f, fade(0, 60), 0f)
        assertTrue("Begin gradually shortly after the original 12% scroll start", fade(0, 90) > 0f)
        assertTrue("The photo must still be fading near the current endpoint", fade(0, 480) < 1f)
        assertEquals(1f, fade(0, 499), 0f)
        assertEquals(1f, fade(1, 0), 0f)
        assertEquals(0f, fade(0, 100, Float.NaN), 0f)
    }

    @Test fun scrollFadeIsSmoothMonotonicAndReversible() {
        val values = (0..500 step 2).map { fade(0, it) }
        assertTrue(values.zipWithNext().all { (a, b) ->
            b >= a && b - a < .040f
        })
        val mid = fade(0, 279)
        assertTrue(mid in .45f.. .55f)
        assertEquals(mid, fade(0, 279), 0f)
        assertTrue(fade(0, 340) > mid)
        assertEquals(1f, detailToolbarBackdropAlpha(1f), 0f)
    }

    @Test fun artistAndDetailNowShareExactlyOneMotionCurve() {
        for (height in listOf(420, 650, 900)) {
            for (offset in 0..height step 3) {
                val detail = detailArtworkFadeLikeArtist(0, offset, height.toFloat() + 100f, 100f)
                val artist = com.nikhil.yt.ui.screens.artist.artistArtworkFade(
                    0, offset, height + 100, 100,
                )
                assertEquals("Artist/detail mismatch at height=$height, offset=$offset",
                    artist, detail, 0f)
            }
        }
    }

    @Test fun albumsAndPlaylistsUseRealArtworkHeightNotArtistsShorterPhoto() {
        for (offset in 0..610 step 5) {
            val detail = detailArtistLengthArtworkFade(
                firstVisibleItemIndex = 0,
                offsetPx = offset,
                artistArtworkBottomPx = 600f,
                detailArtworkBottomPx = 680f,
                toolbarBottomPx = 100f,
            )
            val realArtwork = detailArtworkFadeLikeArtist(0, offset, 680f, 100f)
            assertEquals("Use actual album/playlist bottom at offset=$offset",
                realArtwork, detail, 0f)
        }
        assertTrue("The taller cover should remain in transition at artist's old fade end",
            detailArtistLengthArtworkFade(0, 470, 600f, 680f, 100f) < 1f)
        assertTrue("Collection fades after its own measured edge approaches bar",
            detailArtistLengthArtworkFade(0, 530, 600f, 680f, 100f) > 0f)
        assertEquals(1f, detailArtistLengthArtworkFade(0, 579, 600f, 680f, 100f), 0f)
        // Before layout coordinates arrive, keep the artist-height fallback.
        assertEquals(detailArtworkFadeLikeArtist(0, 450, 600f, 100f),
            detailArtistLengthArtworkFade(0, 450, 600f, Float.NaN, 100f), 0f)
        // Narrow screens still seal as soon as their real photo crosses.
        assertEquals(1f, detailArtistLengthArtworkFade(0, 440, 600f, 540f, 100f), 0f)
        assertEquals(1f, detailArtistLengthArtworkFade(1, 0, 600f, 680f, 100f), 0f)
    }

    @Test fun toolbarSurfaceNeverDoubleTintsVisiblePhoto() {
        for (i in 0 until 1000) {
            val alpha = i / 1000f
            assertEquals(
                artistToolbarSurfaceAlpha(alpha),
                detailToolbarBackdropAlpha(alpha),
                0f,
            )
        }
        assertEquals(1f, detailToolbarBackdropAlpha(0f, forceOpaque = true), 0f)
    }
}
