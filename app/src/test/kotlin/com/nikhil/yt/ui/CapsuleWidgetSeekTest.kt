package com.nikhil.yt.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tapping the widget's progress line moves playback to the point that was tapped.
 *
 * Glance has no drag gesture and no way to learn where inside a view a tap landed, so the line is
 * cut into segments that each carry their own fraction. This pins the arithmetic that makes a tap
 * land where it looks like it points: the fraction of a segment is its middle, not its edge, so the
 * error is half a step in either direction rather than a whole step in one.
 */
class CapsuleWidgetSeekTest {
    private val steps = 20

    private fun fractionOf(step: Int) = (step + 0.5f) / steps

    @Test fun eachSegmentSeeksToItsOwnMiddle() {
        assertEquals(0.025f, fractionOf(0), 0.0001f)
        assertEquals(0.475f, fractionOf(9), 0.0001f)
        assertEquals(0.975f, fractionOf(steps - 1), 0.0001f)
    }

    @Test fun noSegmentCanSeekOutsideTheTrack() {
        (0 until steps).forEach { step ->
            val fraction = fractionOf(step)
            assertTrue("step $step gave $fraction", fraction > 0f && fraction < 1f)
        }
    }

    /** Three minutes is an ordinary song; the tap has to land near enough to be useful. */
    @Test fun aTapLandsWithinAFewSecondsOnAnOrdinarySong() {
        val songMs = 3 * 60 * 1000
        val worstErrorMs = songMs * (0.5f / steps)
        assertTrue("worst case is ${worstErrorMs / 1000}s off", worstErrorMs <= 5_000f)
    }

    /** What is drawn solid has to agree with where the line says playback is. */
    @Test fun theFilledPartMatchesThePosition() {
        fun played(progress: Float) = (progress * steps).toInt().coerceIn(0, steps)
        assertEquals(0, played(0f))
        assertEquals(10, played(0.5f))
        assertEquals(steps, played(1f))
        assertEquals("a bad value cannot overflow the line", steps, played(9f))
        assertEquals("nor underflow it", 0, played(-4f))
    }
}
