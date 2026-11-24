package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerModelsTest {
    @Test fun fitsOldCurvesAndBoundsInvalidProfiles() {
        assertEquals(emptyList<Int>(), resampleEqLevelsByIndex(listOf(1, 2), 0))
        assertEquals(List(10) { 0 }, resampleEqLevelsByIndex(emptyList(), 10))
        assertEquals(listOf(0, 500, 1000, 1500, 2000), resampleEqLevelsByIndex(listOf(0, 1000, 2000), 5))
        assertEquals(32, resampleEqLevelsByIndex(listOf(1, 2), 1024).size)
        assertEquals(listOf(0), resampleEqLevelsByIndex(listOf(Int.MIN_VALUE, Int.MAX_VALUE), 1))
        assertTrue(resampleEqLevelsByIndex(listOf(Int.MIN_VALUE, Int.MAX_VALUE), 3)[1] in -10..10)
        assertEquals(5, capsuleMusicalEqLevels(listOf(120, 210, -90, 50, 25)).size)
        assertEquals(emptyList<Int>(), decodeEqBandLevels("invalid"))
        assertEquals(emptyList<Int>(), decodeEqBandLevels("[" + "1,".repeat(9000) + "0]"))
        assertEquals(listOf(1500, -1500), decodeEqBandLevels("[2147483647,-2147483648]"))
        assertEquals(64, decodeEqBandLevels("[" + List(256) { "100" }.joinToString(",") + "]").size)
    }
    @Test fun savedManualPointsAreReprojectedInFrequencySpace() {
        val raw = "[0,25,50,75,100,125,150,175,200,225]"
        assertEquals(listOf(15, 84, 117, 149, 222), readStudioManualCurve("adaptive:manual", raw))
        assertEquals(listOf(8, 82, 121, 156, 225), readStudioManualCurve("studio:manual", raw))
        assertEquals(listOf(0, 50, 100, 150, 200),
            readStudioManualCurve(CAPSULE_STUDIO_MANUAL_ID, "[0,50,100,150,200]"))
        assertEquals(emptyList<Int>(), readStudioManualCurve("flat", raw))
    }

    @Test fun mixerMigrationAndRestoredAmountsAreIndependentOfEq() {
        val legacy = CapsuleMixerProfile.resolve(null, "capsule:studio_tape", true)
        assertEquals(CapsuleMixerSetting(CapsuleMixerEffect.TAPE, 72), legacy.setting)
        assertEquals(CapsuleMixerEffect.OFF, CapsuleMixerProfile.resolve(null, "studio_tape", false).effect)
        val softened = legacy.withAmount(CapsuleMixerEffect.SOFT, 888)
        val switched = softened.copy(effect = CapsuleMixerEffect.OFF)
        assertEquals(CapsuleMixerEffect.OFF, CapsuleMixerProfile.resolve(switched.encode(), "studio_tape", true).effect)
        val restored = CapsuleMixerProfile.resolve(softened.encode(), "studio_clear", false)
        assertEquals(CapsuleMixerSetting(CapsuleMixerEffect.SOFT, 100), restored.setting)
        assertEquals(72, restored.copy(effect = CapsuleMixerEffect.TAPE).setting.amount)
        assertEquals(0, CapsuleMixerProfile.resolve("sun:1,2,-99,4", null, false).setting.amount)
        assertEquals(CapsuleMixerEffect.OFF, CapsuleMixerProfile.resolve("malformed", null, true).effect)
    }
}
