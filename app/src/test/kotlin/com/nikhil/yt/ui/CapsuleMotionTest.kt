package com.nikhil.yt.ui

import com.nikhil.yt.ui.motion.CapsuleMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
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

    /**
     * The navigation bar has two independent reasons to be out of the way, and they overlap every
     * time the player is closed onto a bar that is coming back. What used to happen there is pinned
     * against here: a sum that ran past the end of the travel, and a branch that snapped.
     */
    @Test fun `two reasons to hide combine into one movement`() {
        // Either one alone is simply itself: the bar moves at that animation's speed, not some
        // blend of two.
        assertEquals(0.4f, CapsuleMotion.either(0.4f, 0f), 1e-6f)
        assertEquals(0.4f, CapsuleMotion.either(0f, 0.4f), 1e-6f)
        // Order cannot matter.
        assertEquals(
            CapsuleMotion.either(0.3f, 0.8f),
            CapsuleMotion.either(0.8f, 0.3f),
            1e-6f,
        )
    }

    @Test fun `overlapping reasons never push past the end of the travel`() {
        // Adding them did, and the bar then had to travel back from beyond its own bounds.
        (0..20).forEach { a ->
            (0..20).forEach { b ->
                val combined = CapsuleMotion.either(a / 20f, b / 20f)
                assertTrue("$a,$b gave $combined", combined in 0f..1f)
            }
        }
    }

    @Test fun `hidden by one reason stays hidden whatever the other does`() {
        (0..20).forEach { other ->
            assertEquals(1f, CapsuleMotion.either(1f, other / 20f), 1e-6f)
        }
        assertEquals(0f, CapsuleMotion.either(0f, 0f), 0f)
    }

    @Test fun `the combination changes speed continuously as the two cross over`() {
        // Taking the larger of the two is the obvious alternative, and it is the one that tears:
        // at the moment the other reason takes over, the speed changes instantly. The two ramps
        // here cross partway through, exactly as the player's close and the bar's return do.
        //
        // A corner is a jump in speed, not in position, so this measures the change in speed
        // between frames. Taking the larger of the two would register about 0.002 here; anything in
        // that region is a corner someone can see.
        val steps = 400
        fun combined(t: Float) = CapsuleMotion.either(1f - t, 0.7f - 0.2f * t)

        var worstChangeInSpeed = 0f
        (1 until steps).forEach { i ->
            val before = combined((i - 1) / steps.toFloat())
            val at = combined(i / steps.toFloat())
            val after = combined((i + 1) / steps.toFloat())
            worstChangeInSpeed =
                maxOf(worstChangeInSpeed, abs((after - at) - (at - before)))
        }
        assertTrue("speed jumped by $worstChangeInSpeed between frames", worstChangeInSpeed < 1e-4f)
    }

    @Test fun `a broken input cannot move the bar`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 2f).forEach { bad ->
            val result = CapsuleMotion.either(bad, 0.5f)
            assertTrue("non-finite result from $bad", result.isFinite())
            assertTrue("out of range result $result from $bad", result in 0f..1f)
        }
    }
}
