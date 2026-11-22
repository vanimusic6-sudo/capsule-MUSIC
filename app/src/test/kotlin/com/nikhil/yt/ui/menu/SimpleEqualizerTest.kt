package com.nikhil.yt.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleEqualizerTest {
    private val freq = listOf(60, 250, 910, 3_600, 14_000)

    @Test fun fiveHardwareBandsAreGroupedByFrequencyWithoutFakingBands() {
        assertEquals(listOf(0, 1), simpleEqBandIndices(freq, 5, 0))
        assertEquals(listOf(2, 3), simpleEqBandIndices(freq, 5, 1))
        assertEquals(listOf(4), simpleEqBandIndices(freq, 5, 2))
    }

    @Test fun draggingBassDoesNotOverwriteMidOrTreble() {
        assertEquals(
            listOf(200, 200, 0, 0, 0),
            simpleEqShiftBandGroup(listOf(0, 0, 0, 0, 0), 5, freq, 0, 200, -1500, 1500),
        )
    }

    @Test fun levelsAreClampedAndInvalidHardwareIsSafe() {
        assertEquals(emptyList<Int>(), simpleEqShiftBandGroup(emptyList(), 0, emptyList(), 0, 999, -1500, 1500))
        val result = simpleEqShiftBandGroup(List(5) { 1400 }, 5, freq, 0, 9000, -1500, 1500)
        assertTrue(result.all { it in -1500..1500 })
        assertEquals(listOf(1400, 1400, 1400), result.drop(2))
    }

    @Test fun presetsKeepPhysicalBandCountAndRespectNativeLimits() {
        assertEquals(5, simpleEqPresetLevels(freq, 5, -1500, 1500, "bass").size)
        assertEquals(List(5) { 0 }, simpleEqPresetLevels(freq, 5, -1500, 1500, "flat"))
        assertTrue(simpleEqPresetLevels(freq, 5, -1500, 1500, "bass")[0] >
            simpleEqPresetLevels(freq, 5, -1500, 1500, "bass")[4])
    }
}
