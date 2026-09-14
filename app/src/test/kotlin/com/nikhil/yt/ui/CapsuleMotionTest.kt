package com.nikhil.yt.ui

import com.nikhil.yt.ui.motion.CapsuleMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion vocabulary's guarantees, which are the ones that decide whether a transition can look
 * broken on a device:
 *
 * - identity at both rest states, so no surface keeps a leftover scale, offset or blur;
 * - one rise and one fall, so nothing can read as a shake;
 * - total safety against out-of-range and non-finite input, because springs, drags and restored
 *   state all produce those.
 */
class CapsuleMotionTest {
    private val samples = (0..400).map { it / 400f }

    @Test fun `a coupled pull is identity at both rest states`() {
        assertEquals(0f, CapsuleMotion.pullToward(0f, 0.42f), 1e-6f)
        assertEquals(0f, CapsuleMotion.pullToward(1f, 0.42f), 1e-6f)
        assertEquals(0f, CapsuleMotion.pullAway(0f, 0.40f), 1e-6f)
        assertEquals(0f, CapsuleMotion.pullAway(1f, 0.40f), 1e-6f)
    }

    @Test fun `a coupled pull rises once and falls once`() {
        val values = samples.map { CapsuleMotion.pullToward(it, 0.42f) }
        val peak = values.indexOf(values.max())
        // Strictly one turning point: everything before the peak is non-decreasing, everything
        // after is non-increasing. An oscillation would break one of these.
        (1..peak).forEach {
            assertTrue("fell before the peak at $it", values[it] >= values[it - 1] - 1e-6f)
        }
        (peak + 1 until values.size).forEach {
            assertTrue("rose after the peak at $it", values[it] <= values[it - 1] + 1e-6f)
        }
        assertTrue("the pull never became visible", values.max() > 0.9f)
    }

    @Test fun `a coupled pull stays outside the window at zero`() {
        assertEquals(0f, CapsuleMotion.pullToward(0.5f, 0.42f), 1e-6f)
        assertEquals(0f, CapsuleMotion.pullToward(0.9f, 0.42f), 1e-6f)
    }

    @Test fun `every motion value survives impossible input`() {
        val hostile = listOf(
            -1f, -0.0001f, 1.0001f, 12f,
            Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
        )
        hostile.forEach { value ->
            listOf(
                CapsuleMotion.pullToward(value, 0.42f),
                CapsuleMotion.pullAway(value, 0.42f),
                CapsuleMotion.pullToward(0.2f, value),
                CapsuleMotion.speedBlurPx(value, 9f),
                CapsuleMotion.speedBlurPx(0.5f, value),
                CapsuleMotion.smooth(value),
                CapsuleMotion.staggered(value, 3, 0.085f),
                CapsuleMotion.staggered(0.5f, 3, value),
            ).forEach { result ->
                assertTrue("non-finite result from $value", result.isFinite())
                assertTrue("out of range result $result from $value", result >= 0f)
            }
        }
    }

    @Test fun `blur is exactly zero at rest so a settled surface carries no render effect`() {
        assertEquals(0f, CapsuleMotion.speedBlurPx(0f, 9f), 0f)
        assertEquals(0f, CapsuleMotion.speedBlurPx(CapsuleMotion.pullAway(1f, 0.4f), 9f), 0f)
        assertEquals(0f, CapsuleMotion.speedBlurPx(CapsuleMotion.pullToward(0f, 0.4f), 9f), 0f)
        // ...and never exceeds the radius it was given.
        samples.forEach {
            assertTrue(CapsuleMotion.speedBlurPx(it, 9f) in 0f..9f)
        }
    }

    @Test fun `a staggered entrance always finishes together however long the list is`() {
        listOf(0, 1, 5, 20, 200).forEach { index ->
            assertEquals(
                "item $index never finished",
                1f,
                CapsuleMotion.staggered(1f, index, 0.085f),
                1e-6f,
            )
            assertEquals(
                "item $index started early",
                0f,
                CapsuleMotion.staggered(0f, index, 0.085f),
                1e-6f,
            )
        }
    }

    @Test fun `a staggered entrance starts later for later items but never stalls`() {
        val early = CapsuleMotion.staggered(0.3f, 0, 0.085f)
        val late = CapsuleMotion.staggered(0.3f, 4, 0.085f)
        assertTrue("a later card did not trail an earlier one", late < early)
        // Even an absurdly long list keeps moving: the delay is capped, so no card waits out the
        // whole entrance and then jumps.
        assertTrue(CapsuleMotion.staggered(0.99f, 500, 0.085f) > 0.5f)
    }

    @Test fun `smoothstep has no slope at either end`() {
        assertEquals(0f, CapsuleMotion.smooth(0f), 1e-6f)
        assertEquals(1f, CapsuleMotion.smooth(1f), 1e-6f)
        assertTrue(CapsuleMotion.smooth(0.02f) < 0.02f)
        assertTrue(CapsuleMotion.smooth(0.98f) > 0.98f)
    }
}
