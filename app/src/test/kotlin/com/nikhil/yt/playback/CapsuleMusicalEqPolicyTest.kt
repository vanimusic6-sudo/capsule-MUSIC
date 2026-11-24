package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class CapsuleMusicalEqPolicyTest {
    @Test fun flatAndUniformCurvesDoNotChangeLoudness() {
        for (level in listOf(-600, 0, 600)) {
            assertEquals(List(10) { 0 }, capsuleMusicalEqLevels(List(10) { level }))
        }
    }

    @Test fun gradualWidespreadBoostAndCutsAreRetained() {
        val raw = listOf(70, 130, 250, 280, 180, 30, -70, -160, -210, -250)
        assertEquals(raw, capsuleMusicalEqLevels(raw))
    }

    @Test fun aSharpMidrangeHoleSoftensWhileItsNeighborsStayDistinct() {
        val raw = listOf(70, 140, 220, 330, 320, -1500, 450, 350, 160, 100)
        val curve = capsuleMusicalEqLevels(raw)
        assertTrue(curve[5] > -250)
        assertTrue(curve[4] > curve[5])
        assertTrue(curve[6] > curve[5])
    }

    @Test fun restoredAndExtremeCurvesStayInsideEveryBandLimit() {
        val random = Random(1)
        repeat(500) {
            val raw = List(random.nextInt(1, 65)) { random.nextInt(-2_000, 2_001) }
            val curve = capsuleMusicalEqLevels(raw)
            assertEquals(10, curve.size)
            assertTrue(curve.indices.all { i -> curve[i] in capsuleStudioBandLimit(CAPSULE_STUDIO_FREQUENCIES_HZ[i]) })
            assertTrue(curve.max() - curve.min() <= CAPSULE_EQ_MAX_CONTRAST_MB)
            assertTrue(curve.zipWithNext().all { (left, right) -> abs(left - right) <= CAPSULE_EQ_MAX_STEP_MB })
        }
    }
}
