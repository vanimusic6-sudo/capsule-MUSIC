package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CapsuleEqPresetsTest {
    private val centers = listOf(60, 230, 910, 3_600, 14_000)

    @Test fun fiveLowContrastVersionsWithNoSevere910HzNotch() {
        val presets = CapsuleEqPresets.all
        assertEquals(5, presets.size)
        assertEquals(5, presets.map { it.id }.distinct().size)
        assertEquals(5, presets.map { it.referenceLevelsMb }.distinct().size)
        assertNull(CapsuleEqPresets.byId("deep_bass"))
        assertNull(CapsuleEqPresets.byId("v_shape"))
        presets.forEach { preset ->
            assertEquals(5, preset.referenceLevelsMb.size)
            assertTrue("Never use sharp +/- dB bands: ${preset.id}",
                preset.referenceLevelsMb.all { it in -120..120 })
            assertTrue("Avoid the previous deep 910 Hz vacuum",
                preset.referenceLevelsMb[2] in -100..100)
            assertTrue("Tonal range should be subtle",
                preset.referenceLevelsMb.max() - preset.referenceLevelsMb.min() <= 200)
        }
    }

    @Test fun eachFiveBandVersionHasExactAuthoredCurve() {
        CapsuleEqPresets.all.forEach { preset ->
            assertEquals(preset.referenceLevelsMb,
                CapsuleEqPresets.levelsFor(preset, centers, -1500, 1500))
        }
    }

    @Test fun hardwareMappingInterpolatesByFrequencyNotBandIndex() {
        val softBass = CapsuleEqPresets.byId("soft_bass_v2")!!
        assertEquals(listOf(80,20,-35),
            CapsuleEqPresets.levelsFor(softBass, listOf(60,910,14000),-1500,1500))
        assertEquals(10,CapsuleEqPresets.levelsFor(softBass,
            listOf(30,60,100,230,400,910,1800,3600,7000,18000),
            -1500,1500).size)
        assertEquals(emptyList<Int>(),CapsuleEqPresets.levelsFor(
            softBass, emptyList(),-1500,1500))
        assertEquals(List(5){0},CapsuleEqPresets.levelsFor(
            softBass, centers,100,-100))
    }

    @Test fun noIndependentNativeBoostsOrHighGainImported() {
        val src=File("src/main/kotlin/com/nikhil/yt/playback/CapsuleEqPresets.kt").readText()
        assertFalse(src.contains("BassBoost("))
        assertFalse(src.contains("Virtualizer("))
        assertFalse(src.contains("LoudnessEnhancer("))
    }
}
