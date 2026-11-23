package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleEqPresetsTest {
    private val centers = listOf(60, 230, 910, 3_600, 14_000)

    @Test fun exactlySixDistinctCurvesWithNonDuplicateIdsAndNames() {
        val presets = CapsuleEqPresets.all
        assertEquals(6, presets.size)
        assertEquals(6, presets.map { it.id }.distinct().size)
        assertEquals(6, presets.map { it.name }.distinct().size)
        assertEquals(6, presets.map { it.referenceLevelsMb }.distinct().size)
        assertEquals(null, CapsuleEqPresets.byId("invalid"))
        presets.forEach { preset ->
            assertEquals(preset, CapsuleEqPresets.byId(preset.id))
            assertEquals(5, preset.referenceLevelsMb.size)
            assertTrue(preset.referenceLevelsMb.max() - preset.referenceLevelsMb.min() in 350..800)
        }
    }

    @Test fun fiveBandPhonesReceiveExactlyTheDesignedShape() {
        for (preset in CapsuleEqPresets.all) {
            assertEquals(
                preset.name,
                preset.referenceLevelsMb,
                CapsuleEqPresets.levelsFor(preset, centers, -1_500, 1_500),
            )
        }
    }

    @Test fun threeAndTenBandEqualizersGetFrequencyMappedCurves() {
        val deep = CapsuleEqPresets.byId("deep_bass")!!
        assertEquals(listOf(380, 40, -100),
            CapsuleEqPresets.levelsFor(deep, listOf(60, 910, 14_000), -1500, 1500))
        val multi = CapsuleEqPresets.levelsFor(
            deep, listOf(30, 60, 100, 230, 400, 910, 1_800, 3_600, 7_000, 18_000),
            -1_500, 1_500,
        )
        assertEquals(10, multi.size)
        assertEquals(380, multi[0])
        assertEquals(-100, multi.last())
        assertNotEquals(multi.first(), multi[4])
    }

    @Test fun invalidOrMissingFrequencyMetadataFallsBackSafely() {
        val preset = CapsuleEqPresets.all.first()
        assertEquals(emptyList<Int>(), CapsuleEqPresets.levelsFor(preset, emptyList(), -100, 100))
        assertEquals(5, CapsuleEqPresets.levelsFor(preset, List(5) { 0 }, -100, 100).size)
        assertEquals(1, CapsuleEqPresets.levelsFor(preset, listOf(0), -100, 100).size)
        assertTrue(CapsuleEqPresets.levelsFor(preset, List(5) { 0 }, -100, 100)
            .all { it in -100..100 })
        assertEquals(List(5) { 0 },
            CapsuleEqPresets.levelsFor(preset, centers, 100, -100))
    }

    @Test fun profileStrengthClampsToHardwareLimitsAndDoesNotForceEffectsOn() {
        val preset = CapsuleEqPresets.byId("deep_bass")!!
        assertEquals(listOf(380, 450, 40, -150, -100),
            CapsuleEqPresets.levelsFor(preset, centers, -500, 500))
        val s = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/playback/CapsuleEqPresets.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/playback/CapsuleEqPresets.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(!s.contains("BassBoost("))
        assertTrue(!s.contains("Virtualizer("))
        assertTrue(!s.contains("LoudnessEnhancer("))
    }
}
