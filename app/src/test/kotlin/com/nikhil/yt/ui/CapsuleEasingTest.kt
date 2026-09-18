package com.nikhil.yt.ui

import androidx.compose.animation.core.Easing
import com.nikhil.yt.ui.motion.CapsuleEnterEasing
import com.nikhil.yt.ui.motion.CapsuleExitEasing
import com.nikhil.yt.ui.motion.CapsuleShortestVisible
import com.nikhil.yt.ui.motion.CapsuleStandardEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What makes a Capsule curve soft, stated as arithmetic.
 *
 * "Softer but the same trajectory" is a real, checkable property: the endpoints and the duration of
 * every animation stay exactly as they were, and only the speed at which the movement is spent
 * changes. The one thing that has to hold for that to feel soft is that the movement starts from
 * rest and arrives at rest — a curve that leaves at speed is what reads as a jolt, and a curve that
 * is still moving when it stops is what reads as a cut.
 */
class CapsuleEasingTest {
    private val curves =
        mapOf(
            "enter" to CapsuleEnterEasing,
            "exit" to CapsuleExitEasing,
            "standard" to CapsuleStandardEasing,
        )

    @Test
    fun everyCurveCoversTheWholeTravel() {
        curves.forEach { (name, easing) ->
            assertEquals("$name does not start at the start", 0f, easing.transform(0f), 1e-4f)
            assertEquals("$name does not reach its target", 1f, easing.transform(1f), 1e-4f)
        }
    }

    @Test
    fun noCurveEverGoesBackwards() {
        curves.forEach { (name, easing) ->
            var previous = 0f
            (0..500).forEach { step ->
                val value = easing.transform(step / 500f)
                assertTrue("$name reverses at ${step / 500f}", value >= previous - 1e-5f)
                previous = value
            }
        }
    }

    @Test
    fun noCurveOvershootsItsTarget() {
        curves.forEach { (name, easing) ->
            (0..500).forEach { step ->
                val value = easing.transform(step / 500f)
                assertTrue("$name leaves its range at ${step / 500f}: $value", value in -1e-4f..1.0001f)
            }
        }
    }

    /**
     * The jolt, measured.
     *
     * Compose's default, `FastOutSlowIn`, has already spent about a fifth of the travel by the time
     * a tenth of the duration has passed. That head start is the jerk that was felt at the beginning
     * of an entrance and, worse, at the beginning of an exit.
     */
    @Test
    fun nothingBoltsOffTheLine() {
        curves.forEach { (name, easing) ->
            val spent = easing.transform(0.04f)
            assertTrue("$name has already spent $spent of its travel in its first frames", spent < 0.02f)
        }
    }

    /** And nothing is still travelling at speed when it is supposed to have arrived. */
    @Test
    fun everythingComesToRestRatherThanStopping() {
        curves.forEach { (name, easing) ->
            val remaining = 1f - easing.transform(0.96f)
            assertTrue("$name still has $remaining to cover in its last frames", remaining < 0.02f)
        }
    }

    /** An arrival is what the eye follows, so it keeps the most visible tail of the three. */
    @Test
    fun anArrivalSettlesMoreGentlyThanADeparture() {
        assertTrue(
            "an entrance must not resolve sooner than an exit",
            remainingAtThreeQuarters(CapsuleEnterEasing) > remainingAtThreeQuarters(CapsuleExitEasing),
        )
    }


    /**
     * A floor, because easing cannot fix a transition that is over before it is seen.
     *
     * A curve distributes the time it is given; it cannot create any. Below roughly a fifth of a
     * second the whole movement lands inside two or three frames, and the result is not a quick
     * animation but a change the eye did not watch happen.
     */
    @Test
    fun theFloorIsLongEnoughToBeSeen() {
        assertTrue(
            "$CapsuleShortestVisible ms is too few frames to read as movement",
            CapsuleShortestVisible >= 180,
        )
        assertTrue(
            "a floor this long stops being a floor and starts being a pace",
            CapsuleShortestVisible <= 260,
        )
    }

    private fun remainingAtThreeQuarters(easing: Easing) = 1f - easing.transform(0.75f)
}
