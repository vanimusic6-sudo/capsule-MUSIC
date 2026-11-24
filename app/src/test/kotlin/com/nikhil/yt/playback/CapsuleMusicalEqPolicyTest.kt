package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class CapsuleMusicalEqPolicyTest {
    private val hz = listOf(60,230,910,3600,14000)
    private fun settings(levels: List<Int>, balance: Boolean = false) = EqSettings(
        enabled = true, bandLevelsMb = levels,
        outputGainEnabled = false, outputGainMb = 0,
        bassBoostEnabled = false, bassBoostStrength = 0,
        virtualizerEnabled = false, virtualizerStrength = 0,
        musicalBalanceEnabled = balance,
    )

    @Test fun flatAndUniformCurveHaveNoArtificialLoudnessBoost() {
        assertEquals(List(5){0}, capsuleMusicalEqLevels(List(5){0},hz))
        assertEquals(List(5){0}, capsuleMusicalEqLevels(List(5){600},hz))
        assertEquals(List(5){0}, capsuleMusicalEqLevels(List(5){-600},hz))
        assertEquals(emptyList<Int>(),capsuleMusicalEqLevels(emptyList()))
    }

    @Test fun musicalGradualContourIsNotAutomaticallyMutedOrFlattened() {
        val gentle=listOf(180,250,140,-80,-120)
        assertEquals("Gentle slopes should be left at full requested intensity",
            gentle,capsuleMusicalEqLevels(gentle,hz))
        val broad=listOf(200,260,80,-100,-160)
        assertEquals(broad,capsuleMusicalEqLevels(broad,hz))
    }

    @Test fun severe910HzVacuumIsLiftedRelativeToItsNeighbors() {
        val raw=listOf(400,900,-1500,900,900)
        val shaped=capsuleMusicalEqLevels(raw,hz)
        assertEquals(raw,listOf(400,900,-1500,900,900))
        assertTrue("Deep 910 Hz notch should be softened",shaped[2] > -500)
        assertTrue("Bass character must remain present",shaped[1] > shaped[2])
        assertTrue("Avoid huge shaped level span",
            capsuleEqCurveSpreadMb(shaped)<=CAPSULE_MUSICAL_MAX_SPREAD_MB)
        assertTrue("Correct severe adjacent differences",
            shaped.zipWithNext().all{(a,b)->abs(a-b)<=CAPSULE_MUSICAL_MAX_NEIGHBOR_MB})
    }

    @Test fun gradientsAreAdaptedAcrossFiveAndManyBands() {
        val bad=List(32){ if(it%2==0) Int.MAX_VALUE else Int.MIN_VALUE }
        val adjusted=capsuleMusicalEqLevels(bad,listOf(0,-1,Int.MAX_VALUE))
        assertEquals(32,adjusted.size)
        assertTrue(adjusted.all{it in -460..320})
        assertTrue(capsuleEqCurveSpreadMb(adjusted)<=CAPSULE_MUSICAL_MAX_SPREAD_MB)
        assertTrue(adjusted.zipWithNext().all{(a,b)->
            abs(a-b)<=CAPSULE_MUSICAL_MAX_NEIGHBOR_MB})
        val two=capsuleMusicalEqLevels(listOf(10000,-10000),listOf(70,2000))
        assertEquals(2,two.size)
        assertTrue(capsuleEqCurveSpreadMb(two)<=CAPSULE_MUSICAL_MAX_SPREAD_MB)
    }

    @Test fun strongerBroadProfilesStayDistinctAndAvoidDeepVocalNotches() {
        val frequencies = CAPSULE_STUDIO_FREQUENCIES_HZ
        val bass = CapsuleEqPresets.byId("soft_bass_v2")!!
        val raw = CapsuleEqPresets.levelsFor(bass, frequencies, -1500, 1500)
        assertTrue("Audible low-mid warmth", raw[3] >= 220)
        val out = capsuleMusicalEqLevels(raw, frequencies)
        assertTrue(out[3] > out[7] + 180)
        assertTrue(out[3] > -200)
        assertTrue(capsuleEqCurveSpreadMb(out) <= CAPSULE_MUSICAL_MAX_SPREAD_MB)
    }

    @Test fun restoredManualCurvesStillCannotBypassDSPGuard() {
        val raw=listOf(2000,2000,-1500,2000,2000)
        val a=protectedEqLevels(settings(raw,false),5,-1500,1500,hz)
        val b=protectedEqLevels(settings(raw,true),5,-1500,1500,hz)
        assertEquals(a,b)
        assertTrue(a.first()<=CAPSULE_MAX_LOW_BASS_BOOST_MB)
        assertTrue(a.all{it<=CAPSULE_MAX_EQ_BOOST_MB})
        val service=File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        assertTrue(service.contains("manualSelected = selectedId == \"adaptive:manual\""))
        assertTrue(service.contains("musicalBalanceEnabled = true"))
        val menu=File("src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt").readText()
        assertTrue(menu.contains("CapsuleEqualizerGraph("))
        assertFalse(menu.contains("setMusicalBalance"))
    }
}
