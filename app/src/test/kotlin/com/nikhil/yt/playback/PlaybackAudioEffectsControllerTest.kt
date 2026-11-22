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
        assertEquals(listOf(300, 650, -400, -1000, 650),
            protectedEqLevels(base, 5, -1500, 1500))
        assertEquals(listOf(0, 0, 0, 0, 0),
            protectedEqLevels(base.copy(enabled = false), 5, -1500, 1500))
        assertEquals(List(5) { 0 }, protectedEqLevels(base, 5, 100, -100))
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
        org.junit.Assert.assertTrue(
            "Strong presets must not make all music whisper",
            factor in 0.62f..0.70f,
        )
        // Tonal EQ boost alone should retain most of the original loudness.
        val eqOnly = base.copy(
            bassBoostEnabled = false, virtualizerEnabled = false,
            outputGainEnabled = false,
        )
        org.junit.Assert.assertTrue(protectedEqAudioFactor(eqOnly) in 0.70f..0.80f)
        val bassOnly = base.copy(
            bandLevelsMb = List(5) { 0 },
            virtualizerEnabled = false, outputGainEnabled = false,
        )
        org.junit.Assert.assertTrue(protectedEqAudioFactor(bassOnly) in 0.80f..0.90f)
        assertEquals(1f, protectedEqAudioFactor(base.copy(
            bandLevelsMb = List(5) { 0 }, outputGainEnabled = false,
            bassBoostEnabled = false, virtualizerEnabled = false,
        )), 0.000001f)
        assertEquals(emptyList<Int>(), protectedEqLevels(base, 0, -1500, 1500))
        org.junit.Assert.assertTrue(
            protectedEqLevels(base, 5, -1000, 400).all { it in -1000..400 })
    }

    @Test
    fun extremeSubBassIsClampedEvenWhenTheOEMReportsWideBandLimits() {
        val values = EqSettings(
            enabled = true, bandLevelsMb = listOf(1500, 1500, 1500),
            outputGainEnabled = false, outputGainMb = 0,
            bassBoostEnabled = false, bassBoostStrength = 0,
            virtualizerEnabled = false, virtualizerStrength = 0,
            intensityPercent = 200,
        )
        assertEquals(
            listOf(300, 650, 650),
            protectedEqLevels(values, 3, -1500, 1500, listOf(60, 230, 1_000)),
        )
        assertEquals(
            listOf(650, 300, 650),
            protectedEqLevels(values, 3, -1500, 1500, listOf(1_000, 90, 5_000)),
        )
        // Some OEM effects report 0 on a failed center-frequency query.
        assertEquals(
            listOf(300, 650, 650),
            protectedEqLevels(values, 3, -1500, 1500, listOf(0, 230, 1_000)),
        )
        val caps = EqCapabilities(3, -1500, 1500, listOf(60, 230, 1000), emptyList())
        org.junit.Assert.assertTrue(protectedEqAudioFactor(values, caps) in 0.70f..0.80f)
    }

    @Test
    fun headroomIsUnityWhenNothingBoostsAndNeverExceedsUnity() {
        val base = EqSettings(
            enabled = true, bandLevelsMb = listOf(-1500, -500, 0),
            outputGainEnabled = true, outputGainMb = -100,
            bassBoostEnabled = true, bassBoostStrength = 0,
            virtualizerEnabled = true, virtualizerStrength = 0,
        )
        assertEquals(1f, protectedEqAudioFactor(base), 0.000001f)
        // The first band might NOT be low bass until the OEM reports its
        // center frequency; missing capabilities must not under-attenuate.
        val unknownFirstBand = base.copy(bandLevelsMb = listOf(1500))
        org.junit.Assert.assertTrue(
            protectedEqAudioFactor(unknownFirstBand) in 0.70f..0.80f
        )
        assertEquals(1f, protectedEqAudioFactor(base.copy(enabled = false)), 0.000001f)
        val boost = base.copy(
            bandLevelsMb = listOf(1500, 1500, 1500),
            bassBoostStrength = 1_000, virtualizerStrength = 1_000,
            outputGainMb = 1_000,
        )
        val withGain = protectedEqAudioFactor(boost)
        val withoutGain = protectedEqAudioFactor(boost.copy(outputGainEnabled = false))
        org.junit.Assert.assertTrue(withGain in 0f..1f)
        org.junit.Assert.assertTrue(withGain < withoutGain)
        // Regression: a combination of effects must never attenuate by
        // 15–18 dB as the previous build did.
        org.junit.Assert.assertTrue(withGain >= 0.63f)
        org.junit.Assert.assertTrue(withGain < 0.80f)
    }

    @Test
    fun normalizationCannotUndoNativeEqHeadroom() {
        // Keep half the normalization gain in dB, rather than deleting it
        // altogether and making normalized tracks sound lifeless.
        assertEquals(
            0.5946f, protectedEqPlaybackVolume(1.414f, 1.414f, 0.5f, 1f, true),
            0.002f,
        )
        assertEquals(
            0.5f, protectedEqPlaybackVolume(0.5f, 0.5f, 1f, 1f, true),
            0.001f,
        )
        assertEquals(
            0.8f, protectedEqPlaybackVolume(0.8f, 2f, 1f, 1f, false),
            0.001f,
        )
        assertEquals(
            0f, protectedEqPlaybackVolume(Float.NaN, 1f, 1f, 1f, true),
            0.001f,
        )
        assertEquals(
            0f, protectedEqPlaybackVolume(1f, Float.POSITIVE_INFINITY, 1f, 1f, true),
            0.001f,
        )
        assertEquals(
            1f, protectedEqPlaybackVolume(4f, 1f, 1f, 1f, false),
            0.001f,
        )
    }

    @Test
    fun serviceProtectsMusicButNotTheSeparateVideoAudioSession() {
        val service = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"),
        ).first(java.io.File::isFile).readText()
        org.junit.Assert.assertTrue(service.contains("protectedEqAudioFactor(settings, eqCapabilities.value)"))
        org.junit.Assert.assertTrue(service.contains("protectedEqPlaybackVolume("))
        org.junit.Assert.assertTrue(
            service.contains("if (settings.enabled) soundStudioEnabledForVolume.value = true")
        )
        val nativeApply = service.indexOf("audioEffectsController.applySettings(settings)")
        val removeBoost = service.indexOf("soundStudioEnabledForVolume.value = false")
        org.junit.Assert.assertTrue(nativeApply >= 0 && removeBoost > nativeApply)
        org.junit.Assert.assertTrue(service.contains("soundStudioTransitionFactor.value = fade"))
        org.junit.Assert.assertTrue(service.contains(
            "videoCompanion?.setVolume(hearingCareOutputVolume(base, ceiling))"))
        org.junit.Assert.assertTrue(service.contains(
            "hearingCareOutputVolume(protected, ceiling)"))
        org.junit.Assert.assertTrue(service.contains(
            "if (settings.enabled) PlaybackParameters(speed, pitch)"))
        org.junit.Assert.assertTrue(service.contains(
            "else PlaybackParameters(1f, 1f)"))
        org.junit.Assert.assertFalse(service.contains(
            "audioEffectsController.applySettings(settings)\\n            }"))
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
        org.junit.Assert.assertTrue(apply.contains("protectedEqLevels("))
        org.junit.Assert.assertTrue(apply.contains("appliedEnabled == true"))
        org.junit.Assert.assertFalse(apply.contains("eq.enabled = false"))
        org.junit.Assert.assertTrue(apply.contains("effect.setStrength(target.toShort())"))
        org.junit.Assert.assertTrue(apply.contains("effect.setTargetGain(targetMb)"))
        org.junit.Assert.assertFalse(apply.contains("effect.enabled = settings.bassBoostEnabled"))
        org.junit.Assert.assertFalse(apply.contains("effect.enabled = settings.virtualizerEnabled"))
        org.junit.Assert.assertTrue(source.contains("if (audioEffectsSessionId == sessionId) return"))
        org.junit.Assert.assertTrue(source.contains("updatePeakLimiter(settings)"))
        org.junit.Assert.assertTrue(source.contains("attemptedPeakLimiter = true"))
        org.junit.Assert.assertTrue(source.contains("effect.setLimiterAllChannelsTo"))
    }
}
