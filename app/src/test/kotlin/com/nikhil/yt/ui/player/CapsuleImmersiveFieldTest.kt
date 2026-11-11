package com.nikhil.yt.ui.player

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CapsuleImmersiveFieldTest {
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

    @Test fun coloursMergeOnlyAfterTheCoverHasSafelyExtendedIntoThePage() {
        val cover = 0.48f
        assertTrue(immersiveSpatialDiffusion(0.25f, cover) < 0.001f)
        assertTrue(immersiveSpatialDiffusion(cover, cover) in 0.15f..0.55f)
        assertTrue(immersiveSpatialDiffusion(0.9f, cover) > 0.80f)
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
