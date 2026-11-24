package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerModelsTest {
    @Test fun fitsOldCurvesAndBoundsInvalidProfiles() {
        assertEquals(emptyList<Int>(), resampleEqLevelsByIndex(listOf(1, 2), 0))
        assertEquals(List(10) { 0 }, resampleEqLevelsByIndex(emptyList(), 10))
        assertEquals(listOf(0, 500, 1000, 1500, 2000), resampleEqLevelsByIndex(listOf(0, 1000, 2000), 5))
        assertEquals(32, resampleEqLevelsByIndex(listOf(1, 2), 1024).size)
        assertEquals(listOf(0), resampleEqLevelsByIndex(listOf(Int.MIN_VALUE, Int.MAX_VALUE), 1))
        assertTrue(resampleEqLevelsByIndex(listOf(Int.MIN_VALUE, Int.MAX_VALUE), 3)[1] in -10..10)
        assertEquals(10, capsuleMusicalEqLevels(listOf(120, 210, -90, 50, 25)).size)
        assertEquals(emptyList<Int>(), decodeEqBandLevels("invalid"))
        assertEquals(emptyList<Int>(), decodeEqBandLevels("[" + "1,".repeat(9000) + "0]"))
        assertEquals(listOf(1500, -1500), decodeEqBandLevels("[2147483647,-2147483648]"))
        assertEquals(64, decodeEqBandLevels("[" + List(256) { "100" }.joinToString(",") + "]").size)
    }
}
