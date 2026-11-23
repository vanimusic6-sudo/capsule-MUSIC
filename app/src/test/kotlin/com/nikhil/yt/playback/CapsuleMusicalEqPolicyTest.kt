package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CapsuleMusicalEqPolicyTest {
    private val hz = listOf(60,230,910,3600,14000)
    private fun settings(levels: List<Int>, balance: Boolean = false) = EqSettings(
        enabled = true, bandLevelsMb = levels,
        outputGainEnabled = false, outputGainMb = 0,
        bassBoostEnabled = false, bassBoostStrength = 0,
        virtualizerEnabled = false, virtualizerStrength = 0,
        musicalBalanceEnabled = balance,
    )

    @Test fun cleanFlatCurveIsTransparentAndUniformBoostIsNotVolumeGain() {
        assertEquals(List(5) { 0 }, capsuleMusicalEqLevels(List(5) { 0 }, hz))
        assertEquals(List(5) { 0 }, capsuleMusicalEqLevels(List(5) { 600 }, hz))
        assertEquals(emptyList<Int>(), capsuleMusicalEqLevels(emptyList()))
    }

    @Test fun extremeNotchesAndHarshHighsCannotReachTheEngine() {
        val raw = listOf(400,900,-1500,900,900)
        val copy = raw.toList()
        val shaped = capsuleMusicalEqLevels(raw,hz)
        assertEquals(copy,raw)
        assertTrue(shaped[2] >= -420)
        assertTrue(shaped.first() <= 220)
        assertTrue(shaped.last() <= 260)
        assertTrue(capsuleEqCurveSpreadMb(shaped) <= CAPSULE_MUSICAL_MAX_SPREAD_MB)
        assertTrue(shaped.zipWithNext().all { (a,b) ->
            kotlin.math.abs(a-b) <= CAPSULE_MUSICAL_MAX_NEIGHBOR_MB
        })
    }

    @Test fun distinctMusicalCharacterSurvivesWithoutExcessGain() {
        val shaped = capsuleMusicalEqLevels(listOf(180,250,140,-80,-120),hz)
        assertTrue(shaped[1] > 80)
        assertTrue(shaped[1] > shaped[4])
        assertTrue(shaped[4] < 0)
        assertEquals(shaped,capsuleMusicalEqLevels(listOf(180,250,140,-80,-120),hz))
    }

    @Test fun oldBalanceOffFlagCannotBypassSafetyOnAnyDSPPath() {
        val raw = listOf(2000, 2000, -1500, 2000, 2000)
        val off = protectedEqLevels(settings(raw,false),5,-1500,1500,hz)
        val on = protectedEqLevels(settings(raw,true),5,-1500,1500,hz)
        assertEquals(on,off)
        assertTrue(off.first() <= CAPSULE_MAX_LOW_BASS_BOOST_MB)
        assertTrue(off.all { it <= CAPSULE_MAX_EQ_BOOST_MB })
    }

    @Test fun invalidHardwareFrequenciesAndExtremeInputsAreBounded() {
        val levels = capsuleMusicalEqLevels(
            List(32) { i -> if(i%2==0) Int.MAX_VALUE else Int.MIN_VALUE },
            listOf(0,-1,Int.MAX_VALUE),
        )
        assertEquals(32,levels.size)
        assertTrue(levels.all { it in -420..350 })
        assertTrue(capsuleEqCurveSpreadMb(levels) <= CAPSULE_MUSICAL_MAX_SPREAD_MB)
        assertTrue(levels.zipWithNext().all { (a,b) ->
            kotlin.math.abs(a-b) <= CAPSULE_MUSICAL_MAX_NEIGHBOR_MB
        })
    }

    @Test fun broadMusicalBassKeepsItsCharacterAndSharpVacuumIsCorrected() {
        val hz=CAPSULE_ADAPTIVE_REFERENCE_HZ
        val smooth=listOf(70, 160, 195, 175, 110, 30, -20)
        val adjusted=capsuleMusicalEqLevels(smooth, hz)
        assertTrue("Wide bass contour should remain prominent",adjusted[2] > 100)
        assertTrue("Keep high end below bass character",adjusted[2] > adjusted.last())
        val notch=listOf(160,180,160,-350,150,135,100)
        val protected=capsuleMusicalEqLevels(notch, hz)
        assertTrue("Narrow 1 kHz vacuum must be softened",protected[3] > -250)
        assertTrue("Do not flatten neighbour profiles",protected[1] > protected[3])
        assertTrue(protected.zipWithNext().all { (a,b) ->
            kotlin.math.abs(a-b) <= CAPSULE_MUSICAL_MAX_NEIGHBOR_MB
        })
    }

    @Test fun floatingAndBrokenFrequencyMetadataCannotBreakTheContour() {
        val irregular=listOf(500,500,-500,500,-500,500,-500)
        val shaped=capsuleMusicalEqLevels(irregular,listOf(0,0,Int.MAX_VALUE,-2,500,500,500))
        assertEquals(irregular.size,shaped.size)
        assertTrue(shaped.all { it in -420..350 })
        assertTrue(capsuleEqCurveSpreadMb(shaped) <= CAPSULE_MUSICAL_MAX_SPREAD_MB)
    }

    @Test fun extremeBroadShelfCannotBypassGlobalContrast() {
        val bassToTreble=List(32) { index -> if(index < 16) -1500 else 1500 }
        val shaped=capsuleMusicalEqLevels(bassToTreble)
        assertTrue(capsuleEqCurveSpreadMb(shaped) <= CAPSULE_MUSICAL_MAX_SPREAD_MB)
        assertTrue(shaped.zipWithNext().all { (a,b) ->
            kotlin.math.abs(a-b) <= CAPSULE_MUSICAL_MAX_NEIGHBOR_MB
        })
    }

    @Test fun playerEnforcesTheNewGuardEvenForMigratedPreferences() {
        val service = File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        assertTrue(service.contains("musicalBalanceEnabled = true"))
        assertFalse(service.contains("musicalBalanceEnabled = prefs[EqualizerMusicalBalanceKey]"))
        val menu = File("src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt").readText()
        assertTrue(menu.contains("CapsuleEqPresets.all.chunked(2)"))
        assertFalse(menu.contains("setMusicalBalance"))
        assertTrue(menu.substringAfter("fun EqualizerDialog(")
            .substringBefore("@Composable\\nprivate fun CapsuleStudioSlider(")
            .contains("CapsuleEqualizerGraph("))
    }
}
