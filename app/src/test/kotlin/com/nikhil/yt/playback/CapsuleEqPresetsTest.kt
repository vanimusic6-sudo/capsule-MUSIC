package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CapsuleEqPresetsTest {
    private val centers = CapsuleEqPresets.referenceHz

    @Test fun curatedFlavorsHaveNoLargeSingleBandNotches() {
        val profiles = CapsuleEqPresets.all
        assertEquals(5,profiles.size)
        assertEquals(5,profiles.map { it.id }.distinct().size)
        assertNull(CapsuleEqPresets.byId("deep_bass"))
        assertNull(CapsuleEqPresets.byId("v_shape"))
        profiles.forEach { preset ->
            assertEquals(6,preset.referenceLevelsMb.size)
            assertTrue(preset.referenceLevelsMb.all { it in -120..120 })
            assertTrue(preset.referenceLevelsMb.max()-preset.referenceLevelsMb.min()<=200)
            assertEquals(preset.referenceLevelsMb,
                CapsuleEqPresets.levelsFor(preset,centers,-1500,1500))
        }
    }

    @Test fun sixMusicalPositionsAreNotForcedOntoNative910HzBands() {
        assertEquals(listOf(80,180,450,1500,4500,10000),centers)
        val soft=CapsuleEqPresets.byId("soft_bass_v2")!!
        assertEquals(listOf(70,60,-20),
            CapsuleEqPresets.levelsFor(soft,listOf(60,450,14000),-1500,1500))
        val dynamic=CapsuleEqPresets.mapReferenceLevels(
            listOf(100,150,40,-20,-50,-80), listOf(60,230,910,3600,14000),
            -1500,1500)
        assertEquals(5,dynamic.size)
        assertTrue("A gentle 910Hz intermediate point cannot become -7dB",
            dynamic[2] in -50..50)
    }

    @Test fun dynamicInterpolationClampsUntrustedControlsAndBadMetadata() {
        val mapped=CapsuleEqPresets.mapReferenceLevels(
            listOf(Int.MAX_VALUE,Int.MIN_VALUE,0,0,0,Int.MAX_VALUE),
            listOf(0,-100,910,Int.MAX_VALUE),-600,600)
        assertEquals(4,mapped.size)
        assertTrue(mapped.all{it in -350..350})
        assertEquals(emptyList<Int>(),CapsuleEqPresets.mapReferenceLevels(
            List(6){0},emptyList(),-1500,1500))
        assertEquals(List(3){0},CapsuleEqPresets.mapReferenceLevels(
            listOf(100),listOf(80,180,450),-1500,1500))
        assertEquals(List(2){0},CapsuleEqPresets.mapReferenceLevels(
            List(6){100},listOf(80,180),300,-300))
    }

    @Test fun curationsHaveNoIndependentBoosterOrExternalDependencies() {
        val src=File("src/main/kotlin/com/nikhil/yt/playback/CapsuleEqPresets.kt").readText()
        assertFalse(src.contains("BassBoost("))
        assertFalse(src.contains("Virtualizer("))
        assertFalse(src.contains("LoudnessEnhancer("))
    }
}
