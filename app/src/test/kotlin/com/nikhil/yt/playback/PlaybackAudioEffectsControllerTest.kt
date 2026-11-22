package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackAudioEffectsControllerTest {
    @Test
    fun resampleReturnsEmptyForNonPositiveTarget() {
        assertEquals(emptyList<Int>(), resampleEqLevelsByIndex(listOf(1, 2), 0))
        assertEquals(emptyList<Int>(), resampleEqLevelsByIndex(listOf(1, 2), -1))
    }

    @Test
    fun resampleFillsSilenceForMissingLevels() {
        assertEquals(listOf(0, 0, 0), resampleEqLevelsByIndex(emptyList(), 3))
    }

    @Test
    fun resampleKeepsAlreadyMatchingBandCount() {
        val levels = listOf(-100, 0, 100)
        assertEquals(levels, resampleEqLevelsByIndex(levels, 3))
    }

    @Test
    fun resampleAveragesWhenTargetHasSingleBand() {
        assertEquals(listOf(0), resampleEqLevelsByIndex(listOf(-300, 0, 300), 1))
    }

    @Test
    fun resampleInterpolatesAcrossTargetBands() {
        assertEquals(
            listOf(0, 500, 1000, 1500, 2000),
            resampleEqLevelsByIndex(listOf(0, 1000, 2000), 5),
        )
    }
    @Test
    fun curveIntensityUsesNativeLimitsEvenWithExtremeInputs() {
        assertEquals(listOf(-1500, 0, 1500),
            scaleEqBandLevels(listOf(-1200, 0, 1200), 200, -1500, 1500))
        assertEquals(listOf(-1200, 0, 1200),
            scaleEqBandLevels(listOf(-1200, 0, 1200), 100, -1500, 1500))
        assertEquals(listOf(-500, 500),
            scaleEqBandLevels(listOf(-500, 500), -900, -1500, 1500))
        assertEquals(listOf(Int.MIN_VALUE, Int.MAX_VALUE),
            scaleEqBandLevels(listOf(Int.MIN_VALUE, Int.MAX_VALUE), 200,
                Int.MIN_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun inverseCurveLevelLetsUsEditTheVisualEffectiveCurve() {
        assertEquals(250, inverseScaledEqLevel(500, 200, -1500, 1500))
        assertEquals(-250, inverseScaledEqLevel(-500, 200, -1500, 1500))
        assertEquals(750, inverseScaledEqLevel(750, 100, -1500, 1500))
    }

    @Test
    fun disablingWholeStudioGivesFlatCurveAndUnityGain() {
        val base = EqSettings(
            enabled = true,
            bandLevelsMb = listOf(1400, 800, -200, -500, 900),
            outputGainEnabled = true, outputGainMb = 1500,
            bassBoostEnabled = true, bassBoostStrength = 1000,
            virtualizerEnabled = true, virtualizerStrength = 1000,
            intensityPercent = 200,
        )
        assertEquals(listOf(650, 650, -400, -1000, 650),
            protectedEqLevels(base, 5, -1500, 1500))
        assertEquals(listOf(0, 0, 0, 0, 0),
            protectedEqLevels(base.copy(enabled = false), 5, -1500, 1500))
        assertEquals(1f, protectedEqAudioFactor(base.copy(enabled = false)), 0.000001f)
    }

    @Test
    fun combinedHeadroomAttenuatesPowerfulPresetsWithoutAmplifyingTheSource() {
        val base = EqSettings(
            enabled = true, bandLevelsMb = listOf(1400, 800, -200, 0, 900),
            outputGainEnabled = true, outputGainMb = 1500,
            bassBoostEnabled = true, bassBoostStrength = 1000,
            virtualizerEnabled = true, virtualizerStrength = 1000,
            intensityPercent = 200,
        )
        val factor = protectedEqAudioFactor(base)
        org.junit.Assert.assertTrue("Strong EQ must reserve headroom", factor in 0.1f..0.5f)
        assertEquals(1f, protectedEqAudioFactor(base.copy(
            bandLevelsMb = List(5) { 0 }, outputGainEnabled = false,
            bassBoostEnabled = false, virtualizerEnabled = false,
        )), 0.000001f)
        assertEquals(emptyList<Int>(), protectedEqLevels(base, 0, -1500, 1500))
        org.junit.Assert.assertTrue(
            protectedEqLevels(base, 5, -1000, 400).all { it in -1000..400 })
    }

    @Test
    fun nativeMixerIsBypassedWithoutDisconnectingEffectGraph() {
        val file = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/playback/PlaybackAudioEffectsController.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/playback/PlaybackAudioEffectsController.kt"),
        ).first(java.io.File::isFile)
        val source = file.readText()
        val apply = source.substringAfter("private fun applySettingsToEffects(settings: EqSettings)")
            .substringBefore("companion object")
        org.junit.Assert.assertTrue(apply.contains("protectedEqLevels(settings"))
        org.junit.Assert.assertTrue(apply.contains("appliedEnabled == true"))
        org.junit.Assert.assertFalse(apply.contains("eq.enabled = false"))
        org.junit.Assert.assertTrue(apply.contains("effect.setStrength(target.toShort())"))
        org.junit.Assert.assertTrue(apply.contains("effect.setTargetGain(targetMb)"))
        org.junit.Assert.assertFalse(apply.contains("effect.enabled = settings.bassBoostEnabled"))
        org.junit.Assert.assertFalse(apply.contains("effect.enabled = settings.virtualizerEnabled"))
        org.junit.Assert.assertTrue(source.contains("if (audioEffectsSessionId == sessionId) return"))
    }
}
