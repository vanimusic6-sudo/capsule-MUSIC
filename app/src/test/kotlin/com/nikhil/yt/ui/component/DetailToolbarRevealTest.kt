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

    @Test fun titleHandoffStartsOnlyWhenCaptionActuallyApproachesToolbar() {
        val toolbarBottom = 100f
        val titleHeight = 30f
        val lead = 12f

        assertEquals(0f, detailTitleOcclusionFraction(140f, toolbarBottom, titleHeight, lead), 0f)
        assertEquals(0f, detailTitleOcclusionFraction(112f, toolbarBottom, titleHeight, lead), 0f)

        val justBeforeContact =
            detailTitleOcclusionFraction(106f, toolbarBottom, titleHeight, lead)
        assertTrue("Dissolve may begin only a little before physical contact",
            justBeforeContact in 0f..0.5f)

        val atContact =
            detailTitleOcclusionFraction(100f, toolbarBottom, titleHeight, lead)
        assertTrue("At the toolbar edge the hand-off must be underway, not complete",
            atContact in 0.2f..0.7f)

        assertEquals(
            1f,
            detailTitleOcclusionFraction(
                toolbarBottom - titleHeight * 1.32f,
                toolbarBottom,
                titleHeight,
                lead,
            ),
            .0001f,
        )
    }

    @Test fun partialTitleHandoffSettlesToNearestMeasuredAnchor() {
        assertEquals(
            100f,
            detailTitleSettleTargetOffset(118f, 100f, 160f, .30f) ?: Float.NaN,
            .0001f,
        )
        assertEquals(
            160f,
            detailTitleSettleTargetOffset(142f, 100f, 160f, .70f) ?: Float.NaN,
            .0001f,
        )
        assertEquals(null, detailTitleSettleTargetOffset(100f, 100f, 160f, 0f))
        assertEquals(null, detailTitleSettleTargetOffset(160f, 100f, 160f, 1f))
        assertEquals(null, detailTitleSettleTargetOffset(108f, 100f, 160f, .10f))
        assertEquals(null, detailTitleSettleTargetOffset(154f, 100f, 160f, .92f))
    }

    @Test fun geometryProgressStaysLinearSoVisualEasingIsNotStacked() {
        val toolbarBottom = 100f
        val titleHeight = 30f
        val lead = 12f
        val startTop = toolbarBottom + lead
        val endTop = toolbarBottom - titleHeight * 1.32f
        val midpoint = (startTop + endTop) * .5f

        assertEquals(
            .5f,
            detailTitleOcclusionFraction(midpoint, toolbarBottom, titleHeight, lead),
            .0001f,
        )
    }

    @Test fun toolbarTitleStartsOnlyAfterHeroTitleIsFullyGone() {
        var seenToolbar = false
        var longestNearBlankRun = 0
        var nearBlankRun = 0
        for (i in 0..1000) {
            val progress = i / 1000f
            val cover = detailCoverTitleAlpha(progress)
            val toolbar = detailToolbarTitleAlpha(progress)
            assertTrue(cover in 0f..1f)
            assertTrue(toolbar in 0f..1f)

            if (toolbar > .0001f) {
                seenToolbar = true
                assertEquals(
                    "Toolbar title cannot appear while the hero title is still visible at $progress",
                    0f,
                    cover,
                    .0001f,
                )
            }

            if (cover + toolbar < .05f) {
                nearBlankRun++
                longestNearBlankRun = maxOf(longestNearBlankRun, nearBlankRun)
            } else {
                nearBlankRun = 0
            }
        }
        assertTrue("Toolbar title must eventually appear", seenToolbar)
        assertTrue(
            "The no-title seam must be extremely narrow, not a catchable dead zone",
            longestNearBlankRun <= 18,
        )
        assertEquals(1f, detailCoverTitleAlpha(0f), 0f)
        assertEquals(0f, detailToolbarTitleAlpha(0f), 0f)
        assertEquals(0f, detailCoverTitleAlpha(1f), 0f)
        assertEquals(1f, detailToolbarTitleAlpha(1f), 0f)
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
