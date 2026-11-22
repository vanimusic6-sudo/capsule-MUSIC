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
    fun enabledEffectsOnlyReceiveNativeWritesOnChanges() {
        val source = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/playback/PlaybackAudioEffectsController.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/playback/PlaybackAudioEffectsController.kt"),
        ).first(java.io.File::isFile).readText()
        org.junit.Assert.assertTrue(source.contains(
            "settings.bassBoostEnabled && appliedBassStrength != desiredStrength"))
        org.junit.Assert.assertTrue(source.contains(
            "settings.virtualizerEnabled && appliedVirtualizerStrength != desiredStrength"))
        org.junit.Assert.assertTrue(source.contains(
            "appliedBassEnabled != settings.bassBoostEnabled"))
        org.junit.Assert.assertTrue(source.contains(
            "appliedVirtualizerEnabled != settings.virtualizerEnabled"))
        org.junit.Assert.assertFalse(source.contains(
            "effect.setStrength(settings.bassBoostStrength.toShort())"))
        org.junit.Assert.assertTrue(source.contains("equalizer?.let { eq ->"))
        org.junit.Assert.assertFalse(source.contains("val eq = equalizer ?: return"))
        org.junit.Assert.assertTrue(source.contains("if (appliedGainEnabled != settings.outputGainEnabled)"))
    }
}
