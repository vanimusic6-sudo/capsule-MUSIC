package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class CapsuleMusicalEqPolicyTest {
    @Test fun flatAndUniformCurvesDoNotChangeLoudness() {
        for (level in listOf(-600, 0, 600)) {
            assertEquals(List(5) { 0 }, capsuleMusicalEqLevels(List(5) { level }))
        }
    }

    @Test fun gradualWidespreadBoostAndCutsAreRetained() {
        val raw = listOf(70, 220, 180, 30, -180)
        assertEquals(raw, capsuleMusicalEqLevels(raw))
    }

    @Test fun aSharpMidrangeHoleSoftensWhileItsNeighborsStayDistinct() {
        val raw = listOf(70, 320, -1500, 390, 100)
        val curve = capsuleMusicalEqLevels(raw)
        assertTrue(curve[2] > -250)
        assertTrue(curve[1] > curve[2])
        assertTrue(curve[3] > curve[2])
    }

    @Test fun restoredAndExtremeCurvesStayInsideEveryBandLimit() {
        val random = Random(1)
        repeat(500) {
            val raw = List(random.nextInt(1, 65)) { random.nextInt(-2_000, 2_001) }
            val curve = capsuleMusicalEqLevels(raw)
            assertEquals(5, curve.size)
            assertTrue(curve.indices.all { i -> curve[i] in capsuleStudioBandLimit(CAPSULE_STUDIO_FREQUENCIES_HZ[i]) })
            assertTrue(curve.max() - curve.min() <= CAPSULE_EQ_MAX_CONTRAST_MB)
            assertTrue(curve.zipWithNext().all { (left, right) -> abs(left - right) <= CAPSULE_EQ_MAX_STEP_MB })
        }
    }
}
