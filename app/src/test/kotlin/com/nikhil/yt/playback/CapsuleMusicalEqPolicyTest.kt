package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guard shapes tones only; it does not estimate headphone SPL. */
class CapsuleMusicalEqPolicyTest {
    private val hz = listOf(60, 230, 910, 3600, 14000)

    private fun settings(levels: List<Int>, balance: Boolean) = EqSettings(
        enabled = true, bandLevelsMb = levels,
        outputGainEnabled = false, outputGainMb = 0,
        bassBoostEnabled = false, bassBoostStrength = 0,
        virtualizerEnabled = false, virtualizerStrength = 0,
        musicalBalanceEnabled = balance,
    )

    @Test fun extremeNotchAndBoostAreReducedWithoutRewritingUserInput() {
        val original = listOf(400, 900, -1500, 900, 900)
        val copy = original.toList()
        val actual = capsuleMusicalEqLevels(original, hz)
        assertEquals(copy, original)
        assertEquals(5, actual.size)
        assertTrue("raw 24 dB contrast must be reduced", capsuleEqCurveSpreadMb(actual) <= 850)
        assertTrue(actual.zipWithNext().all { (a, b) ->
            kotlin.math.abs(a - b) <= CAPSULE_MUSICAL_MAX_NEIGHBOR_MB
        })
        assertTrue(actual[2] > -1000) // 910 Hz cannot turn into a deep vacuum
        assertTrue(actual[4] <= 450)   // overly bright air-band is restrained
        assertTrue(actual[0] <= 350)   // limited excursion at sub-bass
    }

    @Test fun midrangeVacuumIsReducedWithoutFlatteningTheProfile() {
        val levels = capsuleMusicalEqLevels(listOf(300, 600, -1400, 500, 400), hz)
        assertTrue("Deep 910 Hz notches must soften", levels[2] >= -520)
        assertTrue("Distinct tonal flavor must remain", levels[1] > levels[4])
        assertTrue(capsuleEqCurveSpreadMb(levels) <= CAPSULE_MUSICAL_MAX_SPREAD_MB)
    }

    @Test fun neutralCurveIsStrictlyTransparent() {
        assertEquals(listOf(0, 0, 0, 0, 0),
            capsuleMusicalEqLevels(listOf(0, 0, 0, 0, 0), hz))
        assertEquals(emptyList<Int>(), capsuleMusicalEqLevels(emptyList()))
        assertEquals(0, capsuleEqCurveSpreadMb(emptyList()))
    }

    @Test fun broadGentleCorrectionRemainsAudibleAndDeterministic() {
        val broad = listOf(180, 250, 140, -80, -120)
        val first = capsuleMusicalEqLevels(broad, hz)
        assertEquals(first, capsuleMusicalEqLevels(broad, hz))
        assertTrue(first[1] > first[4])
        assertTrue(first[1] > 80)
        assertTrue(first.all { it in -750..650 })
    }

    @Test fun balancingIsEnabledOnlyWhenSettingRequestsIt() {
        val raw = listOf(400, 900, -1500, 900, 900)
        val precise = protectedEqLevels(
            settings(raw, balance = false), 5, -1500, 1500, hz)
        val musical = protectedEqLevels(
            settings(raw, balance = true), 5, -1500, 1500, hz)
        assertEquals(raw, precise)
        assertFalse(raw == musical)
        assertTrue(capsuleEqCurveSpreadMb(musical) <= 850)
        assertTrue(capsuleSafeEqInputGainMb(settings(raw, true), musical) >
            capsuleSafeEqInputGainMb(settings(raw, false), precise))
    }

    @Test fun manyBandsRemainConstrainedOnBadHardwareFrequencyMaps() {
        val raw = List(32) { index -> if (index % 2 == 0) Int.MAX_VALUE else Int.MIN_VALUE }
        val actual = capsuleMusicalEqLevels(raw, listOf(0, -3, 99))
        assertEquals(32, actual.size)
        assertTrue(actual.all { it in -750..650 })
        assertTrue(capsuleEqCurveSpreadMb(actual) <= CAPSULE_MUSICAL_MAX_SPREAD_MB)
        assertTrue(actual.zipWithNext().all { (a, b) ->
            kotlin.math.abs(a - b) <= CAPSULE_MUSICAL_MAX_NEIGHBOR_MB
        })
    }

    @Test fun flagIsConsumedByRealAudioServiceAndCurveUI() {
        val service = sequenceOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(service.contains("musicalBalanceEnabled = true"))
        assertFalse(service.contains("musicalBalanceEnabled = prefs[EqualizerMusicalBalanceKey]"))
        val menu = sequenceOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(menu.contains("val musicalBalance = true"))
        assertFalse(menu.contains("setMusicalBalance"))
        assertTrue(menu.contains("musicalBalanceEnabled = musicalBalance"))
        assertTrue(menu.contains("appliedLevelsMb = if (eqEnabled && musicalBalance)"))
    }
}
