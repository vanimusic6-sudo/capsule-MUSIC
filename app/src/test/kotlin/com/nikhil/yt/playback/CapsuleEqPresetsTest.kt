package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CapsuleEqPresetsTest {
    private val centers=CAPSULE_TEN_BAND_HZ

    @Test fun fiveBroadTenPointVersionsWithoutVocalNotches() {
        val p=CapsuleEqPresets.all
        assertEquals(5,p.size)
        assertEquals(5,p.map{it.id}.distinct().size)
        assertEquals(5,p.map{it.referenceLevelsMb}.distinct().size)
        assertNull(CapsuleEqPresets.byId("deep_bass"))
        assertNull(CapsuleEqPresets.byId("v_shape"))
        p.forEach{
            assertEquals(10,it.referenceLevelsMb.size)
            assertTrue(it.referenceLevelsMb.all { mb -> mb in -160..290 })
            assertTrue(it.referenceLevelsMb.zipWithNext().all { (a,b) ->
                kotlin.math.abs(a-b)<=160
            })
        }
    }

    @Test fun referenceAnchorsStayFaithfulAndDeviceBandsInterpolateByFrequency() {
        CapsuleEqPresets.all.forEach{p->
            assertEquals(p.referenceLevelsMb,
                CapsuleEqPresets.levelsFor(p,centers,-1500,1500))
        }
        val bass=CapsuleEqPresets.byId("soft_bass_v2")!!
        val three=CapsuleEqPresets.levelsFor(
            bass,listOf(80,1250,16000),-1500,1500)
        assertEquals(listOf(180,100,-110),three)
        assertEquals(10,CapsuleEqPresets.levelsFor(
            bass,listOf(30,60,110,220,450,910,1800,3500,7000,18000),
            -1500,1500).size)
    }

    @Test fun malformedFrequencyDataCannotCrashOrExceedDeviceGainRange() {
        val preset=CapsuleEqPresets.all.first()
        assertEquals(emptyList<Int>(),CapsuleEqPresets.levelsFor(preset,emptyList(),-100,100))
        assertEquals(List(10){0},CapsuleEqPresets.levelsFor(preset,centers,100,-100))
        assertTrue(CapsuleEqPresets.levelsFor(preset,List(10){0},-100,100)
            .all{it in -100..100})
        assertEquals(1,CapsuleEqPresets.levelsFor(preset,listOf(0),-100,100).size)
    }

    @Test fun noExternalEffectsOrAudioSessionBoostsInPresetDefinitions() {
        val s=File("src/main/kotlin/com/nikhil/yt/playback/CapsuleEqPresets.kt").readText()
        assertFalse(s.contains("BassBoost("))
        assertFalse(s.contains("Virtualizer("))
        assertFalse(s.contains("LoudnessEnhancer("))
    }
}
