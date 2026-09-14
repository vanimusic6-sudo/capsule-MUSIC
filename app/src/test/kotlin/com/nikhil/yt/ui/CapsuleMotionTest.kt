package com.nikhil.yt.ui

import com.nikhil.yt.ui.motion.CapsuleMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantees that decide whether a transition can look broken on a device: identity at rest, no
 * reversal on the way there, and total safety against the out-of-range and non-finite values that
 * springs, drags and restored state all produce.
 */
class CapsuleMotionTest {
    private val samples = (0..400).map { it / 400f }

    @Test fun `approach is fully applied when docked and absent once open`() {
        assertEquals(0f, CapsuleMotion.approach(0f, 0.34f), 1e-6f)
        assertEquals(1f, CapsuleMotion.approach(0.34f, 0.34f), 1e-6f)
        // An open surface must carry no transform at all, for the whole rest of its travel — that is
        // what makes an idle player free rather than merely cheap.
        samples.filter { it >= 0.34f }.forEach {
            assertEquals(
                "carried a transform while open at $it",
                1f,
                CapsuleMotion.approach(it, 0.34f),
                1e-6f,
            )
        }
    }

    @Test fun `approach never reverses`() {
        val values = samples.map { CapsuleMotion.approach(it, 0.34f) }
        (1 until values.size).forEach {
            assertTrue("approach reversed at $it", values[it] >= values[it - 1] - 1e-6f)
        }
    }

    @Test fun `every motion value survives impossible input`() {
        val hostile = listOf(
            -1f, -0.0001f, 1.0001f, 12f,
            Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
        )
        hostile.forEach { value ->
            listOf(
                CapsuleMotion.smooth(value),
                CapsuleMotion.approach(value, 0.34f),
                CapsuleMotion.approach(0.2f, value),
            ).forEach { result ->
                assertTrue("non-finite result from $value", result.isFinite())
                assertTrue("out of range result $result from $value", result in 0f..1f)
            }
        }
    }

    @Test fun `smoothstep has no slope at either end`() {
        assertEquals(0f, CapsuleMotion.smooth(0f), 1e-6f)
        assertEquals(1f, CapsuleMotion.smooth(1f), 1e-6f)
        assertTrue(CapsuleMotion.smooth(0.02f) < 0.02f)
        assertTrue(CapsuleMotion.smooth(0.98f) > 0.98f)
    }
}
