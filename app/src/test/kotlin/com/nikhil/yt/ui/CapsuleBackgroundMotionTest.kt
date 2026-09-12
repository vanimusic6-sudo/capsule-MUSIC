package com.nikhil.yt.ui

import com.nikhil.yt.ui.player.CONSTELLATION_PERIOD_MS
import com.nikhil.yt.ui.player.capsuleBackgroundAngle
import com.nikhil.yt.ui.player.capsuleConstellation
import com.nikhil.yt.ui.player.capsuleStarDrift
import com.nikhil.yt.ui.player.constellationBlend
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CapsuleBackgroundMotionTest {
    @Test fun wavesAndStarDriftDoNotJumpAtTheOldThirtySixSecondReset() {
        for (time in listOf(36_000L, 72_000L, 86_400_000L)) {
            val before = capsuleBackgroundAngle(time - 1)
            val after = capsuleBackgroundAngle(time + 1)
            assertTrue(after > before && after - before < 0.001)
            capsuleConstellation(0, 82).forEach { star ->
                val (x1, y1) = capsuleStarDrift(star, time - 1)
                val (x2, y2) = capsuleStarDrift(star, time + 1)
                assertTrue(abs(x2 - x1) < 0.001f && abs(y2 - y1) < 0.001f)
            }
        }
    }

    @Test fun starLayoutsRenewDeterministicallyAndSharePositionsAcrossSurfaces() {
        val first = capsuleConstellation(7, 82)
        assertEquals(first, capsuleConstellation(7, 82))
        assertNotEquals(first, capsuleConstellation(8, 82))
        assertEquals(first.take(30), capsuleConstellation(7, 30))
        assertTrue(first.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test fun oldAndNewStarsCrossfadeWithoutDisappearingAtThePeriodBoundary() {
        assertEquals(0f, constellationBlend(71_999).nextAlpha, 0f)
        assertEquals(0f, constellationBlend(72_000).nextAlpha, 0f)
        assertEquals(0.5f, constellationBlend(78_000).nextAlpha, 0.0001f)
        val before = constellationBlend(CONSTELLATION_PERIOD_MS - 1)
        val after = constellationBlend(CONSTELLATION_PERIOD_MS)
        assertEquals(before.generation + 1, after.generation)
        assertEquals(1f, before.nextAlpha, 0.0001f)
        assertEquals(0f, after.nextAlpha, 0f)
        assertEquals(capsuleConstellation(before.generation + 1, 82), capsuleConstellation(after.generation, 82))
        for (time in 0L..(2L * CONSTELLATION_PERIOD_MS) step 113L) {
            assertTrue(constellationBlend(time).nextAlpha in 0f..1f)
        }
    }
}
