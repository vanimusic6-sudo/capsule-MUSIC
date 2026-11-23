package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CapsuleEqPresetsTest {
    @Test fun exactlyFiveConservativeVersionsAndSevenMusicalAnchors() {
        val presets = CapsuleEqPresets.all
        assertEquals(5, presets.size)
        assertEquals(7, CAPSULE_ADAPTIVE_REFERENCE_HZ.size)
        assertEquals(5, presets.map { it.id }.distinct().size)
        assertNull(CapsuleEqPresets.byId("deep_bass"))
        assertNull(CapsuleEqPresets.byId("soft_bass_v2"))
        for (preset in presets) {
            assertTrue(preset.referenceLevelsMb.all { it in -150..150 })
            assertEquals(7, preset.referenceLevelsMb.size)
        }
    }

    @Test fun fixedCurvesUseLogarithmicHzMappingOnAnyHardware() {
        val preset = CapsuleEqPresets.byId("soft_bass_v3")!!
        assertEquals(preset.referenceLevelsMb,
            CapsuleEqPresets.levelsFor(preset, CAPSULE_ADAPTIVE_REFERENCE_HZ, -1500, 1500))
        assertEquals(listOf(130,20,-30),
            CapsuleEqPresets.levelsFor(preset,listOf(80,1000,13000),-1500,1500))
        val ten = CapsuleEqPresets.levelsFor(
            preset,listOf(30,80,140,180,400,1000,2600,6500,9000,18000),
            -1500,1500)
        assertEquals(10,ten.size)
        assertEquals(130,ten.first())
        assertEquals(-30,ten.last())
        assertTrue(ten.all { it in -150..150 })
        assertEquals(32,CapsuleEqPresets.levelsFor(
            preset,List(300) { 0 }, -1500,1500).size)
    }

    @Test fun hostileFrequencyAndGainMetadataStayBounded() {
        assertEquals(emptyList<Int>(),capsuleAdaptiveLevelsFor(
            List(7){Int.MAX_VALUE},emptyList(),-1500,1500))
        assertEquals(List(5){0},capsuleAdaptiveLevelsFor(
            List(4){150},List(5){0},-1500,1500))
        assertEquals(List(5){0},capsuleAdaptiveLevelsFor(
            List(7){150},List(5){100},20,-20))
        val invalid=capsuleAdaptiveLevelsFor(
            List(7){ if(it%2==0) Int.MAX_VALUE else Int.MIN_VALUE },
            listOf(0,1,Int.MAX_VALUE,-1,2000),-200,200)
        assertTrue(invalid.all { it in -200..200 })
        assertEquals(5,invalid.size)
    }

    @Test fun manualVersionMatchesTheStoredMusicalGrid() {
        val points=listOf(0,300,250,-300,100,-200,0)
        val mapped=capsuleAdaptiveLevelsFor(
            points,CAPSULE_ADAPTIVE_REFERENCE_HZ,-1500,1500)
        assertEquals(points,mapped)
        val src=File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        assertTrue(src.contains("selected == CAPSULE_ADAPTIVE_MANUAL_VERSION"))
        assertTrue(src.contains("&& (preset != null || manual)"))
        assertTrue(src.contains("versionId = if (manual) CAPSULE_ADAPTIVE_MANUAL_VERSION"))
    }
}
