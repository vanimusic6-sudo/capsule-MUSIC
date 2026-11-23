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

    @Test fun playerEnforcesTheNewGuardEvenForMigratedPreferences() {
        val service = File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        assertTrue(service.contains("musicalBalanceEnabled = true"))
        assertFalse(service.contains("musicalBalanceEnabled = prefs[EqualizerMusicalBalanceKey]"))
        val menu = File("src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt").readText()
        assertTrue(menu.contains("val musicalBalance = true"))
        assertFalse(menu.contains("setMusicalBalance"))
    }
}
