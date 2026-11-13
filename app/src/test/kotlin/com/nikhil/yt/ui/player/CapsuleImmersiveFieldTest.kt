package com.nikhil.yt.ui.player

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CapsuleImmersiveFieldTest {
    @Test fun featherRevealsPreparedBackdropWithSmoothEndpoints() {
        assertTrue("Backdrop must exist before any photo alpha is removed",
            IMMERSIVE_DIFFUSION_START < IMMERSIVE_PHOTO_FEATHER_START)
        assertTrue("Only the lower portion of the artwork should be softened",
            IMMERSIVE_PHOTO_FEATHER_START in 0.62f..0.72f)
        assertTrue(immersivePhotoFeatherAlpha(0f) == 1f)
        assertTrue(immersivePhotoFeatherAlpha(1f) == 0f)
        var previous = 1f
        for (i in 0..1000) {
            val y = i / 1000f
            val next = immersivePhotoFeatherAlpha(y)
            assertTrue("Feather cannot form a second opaque stripe", next <= previous + 0.00001f)
            assertTrue("No sharp scanline in the dissolve", previous - next < 0.017f)
            previous = next
        }
        assertTrue(immersivePhotoFeatherAlpha(IMMERSIVE_PHOTO_FEATHER_START + 0.001f) > 0.999f)
        assertTrue(immersivePhotoFeatherAlpha(0.999f) < 0.001f)
    }

    @Test fun sharpDetailFadesWhileBlurredPhotoKeepsItsColour() {
        assertTrue(immersivePhotoFeatherAlpha(0.65f) == 1f)
        assertTrue("Half of the fine detail should be gone before the bottom sixth",
            immersivePhotoFeatherAlpha(0.85f) < 0.5f)
        assertTrue("The soft photograph must remain visible inside its own strip",
            immersivePhotoFeatherAlpha(0.94f) in 0.25f..0.40f)
        assertTrue("The photo must still finish at its original boundary",
            immersivePhotoFeatherAlpha(0.999f) < 0.001f)
        for (i in 0..840) {
            val y = i / 1000f
            val t = ((y - IMMERSIVE_PHOTO_FEATHER_START) /
                (1f - IMMERSIVE_PHOTO_FEATHER_START)).coerceIn(0f, 1f)
            val original = 1f - t * t * t * (t * (t * 6f - 15f) + 10f)
            assertTrue("Opacity above the blur strip must remain unchanged",
                abs(immersivePhotoFeatherAlpha(y) - original) < 0.000001f)
        }
    }

    @Test fun strongLeftRightPaletteIsPreservedWithoutDiscreteStripeJumps() {
        val bands = List(7) { index ->
            if (index < 3) Color(0xFFD2484C) else Color(0xFF286ED9)
        }
        val left = immersiveSoftBandColor(bands, 0f)
        val right = immersiveSoftBandColor(bands, 1f)
        // A wider Gaussian haze deliberately blends colours. The sides must
        // remain visibly different, but no longer as sharply separated as before.
        assertTrue(left.red > left.blue + 0.24f)
        assertTrue(right.blue > right.red + 0.30f)
        var largestJump = 0f
        var previous = immersiveSoftBandColor(bands, 0f)
        for (i in 1..100) {
            val next = immersiveSoftBandColor(bands, i / 100f)
            largestJump = maxOf(largestJump,
                abs(next.red - previous.red),
                abs(next.green - previous.green),
                abs(next.blue - previous.blue))
            previous = next
        }
        assertTrue("Continuous blur must not introduce vertical colour bars: $largestJump",
            largestJump < 0.055f)
    }

    @Test fun shortBleedNeverExtendsIntoTheTransportControls() {
        val cover = 0.48f
        val end = capsuleImmersiveBleedEnd(cover, 0.07f)
        assertTrue(end > cover)
        assertTrue("Keep the fade shorter than 9% of the sheet", end - cover < 0.09f)
        assertTrue(capsuleImmersiveBleedEnd(0.50f, 0.08f) < 0.60f)
    }

    @Test fun isolatedDarkEdgeOnPaleCoverCannotDirtyTheDominantTone() {
        val white = Color(0xFFF2F2ED)
        val black = Color(0xFF090909)
        val grid = CapsuleColorField(20, 16, List(20 * 16) { idx ->
            if (idx % 20 < 2) black else white
        })
        val dominant = capsuleDominantEdgeColor(grid)
        assertTrue(dominant.red > 0.85f)
        assertTrue(dominant.green > 0.85f)
        assertTrue(dominant.blue > 0.80f)
    }

    @Test fun strongerHorizontalBlurDoesNotDestroySpatialIdentity() {
        val sample = List(7) { n ->
            if (n < 3) Color(0xFFD2484C) else Color(0xFF286ED9)
        }
        val left = immersiveSoftBandColor(sample, 0f)
        val right = immersiveSoftBandColor(sample, 1f)
        val middle = immersiveSoftBandColor(sample, 0.5f)
        assertTrue(left.red > right.red)
        assertTrue(right.blue > left.blue)
        assertTrue(middle.red > 0.05f && middle.blue > 0.05f)
    }

    @Test fun uniformOrMissingPaletteRemainsStable() {
        val uniform = List(7) { Color(0xFFBDB0AA) }
        assertTrue(abs(immersiveSoftBandColor(uniform, 0.1f).red -
            immersiveSoftBandColor(uniform, 0.9f).red) < 0.001f)
        assertTrue(immersiveSoftBandColor(emptyList(), 0.5f) == IMMERSIVE_NEUTRAL_COLOR)
    }
}
