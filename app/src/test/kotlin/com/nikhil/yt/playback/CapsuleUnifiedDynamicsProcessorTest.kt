package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure policy regressions; Android effect activation is tested on devices. */
class CapsuleUnifiedDynamicsProcessorTest {
    private val caps = EqCapabilities(
        bandCount = 5,
        minBandLevelMb = -1500,
        maxBandLevelMb = 1500,
        centerFreqHz = listOf(60, 230, 910, 3600, 14000),
        systemPresets = emptyList(),
    )
    private fun settings(
        enabled: Boolean = true,
        levels: List<Int> = listOf(0, 0, 0, 0, 0),
        bass: Int = 0,
    ) = EqSettings(
        enabled = enabled,
        bandLevelsMb = levels,
        outputGainEnabled = false,
        outputGainMb = 0,
        bassBoostEnabled = bass > 0,
        bassBoostStrength = bass,
        virtualizerEnabled = false,
        virtualizerStrength = 0,
    )

    @Test fun cutoffsIncreaseAndEndAtTwentyKilohertz() {
        val cutoffs = capsuleDspCutoffsHz(caps.centerFreqHz, 5)
        assertEquals(5, cutoffs.size)
        assertEquals(20000f, cutoffs.last(), 0.1f)
        for (index in 1 until cutoffs.size) {
            assertTrue(cutoffs[index] > cutoffs[index - 1])
        }
        assertTrue(cutoffs.first() in 60f..230f)
    }

    @Test fun invalidFrequencyMetadataDoesNotCreateBrokenFilters() {
        val cutoffs = capsuleDspCutoffsHz(listOf(0, -99, 2, 2, Int.MAX_VALUE), 5)
        assertEquals(5, cutoffs.size)
        for (index in 1 until cutoffs.size) {
            assertTrue(cutoffs[index] > cutoffs[index - 1])
            assertTrue(cutoffs[index] <= 20000f)
        }
        assertTrue(capsuleDspCutoffsHz(emptyList(), 0).isEmpty())
        assertEquals(32, capsuleDspCutoffsHz(emptyList(), 40).size)
    }

    @Test fun nativeBassEffectBecomesOnlyLimitedLowBandGain() {
        val result = capsuleUnifiedEqLevels(settings(
            levels = listOf(1500, 1500, 0, 0, 0),
            bass = 250,
        ), caps)
        assertEquals(listOf(450, 900, 0, 0, 0), result)
        assertEquals(listOf(0, 0, 0, 0, 0),
            capsuleUnifiedEqLevels(settings(enabled = false, bass = 250), caps))
    }

    @Test fun bassDynamicsEngagesOnlyForBoostedLowFrequency() {
        assertTrue(capsuleNeedsBassDynamics(settings(bass = 250), caps))
        assertTrue(capsuleNeedsBassDynamics(
            settings(levels = listOf(400, 0, 0, 0, 0)), caps))
        // Small tonal adjustments must not activate an unnecessary compressor.
        assertTrue(!capsuleNeedsBassDynamics(
            settings(levels = listOf(200, 0, 0, 0, 0)), caps))
        assertTrue(!capsuleNeedsBassDynamics(
            settings(levels = listOf(0, 0, 0, 0, 650)), caps))
        assertTrue(!capsuleNeedsBassDynamics(
            settings(enabled = false, bass = 250), caps))
    }

    @Test fun boostsRespectTheSameBandCapsWhenMetadataIsAbsent() {
        val unknown = caps.copy(centerFreqHz = emptyList())
        val values = capsuleUnifiedEqLevels(settings(
            levels = listOf(1500, 1500, 1500, 1500, 1500),
            bass = 250,
        ), unknown)
        assertEquals(450, values.first())
        assertTrue(values.drop(1).all { it <= 900 })
    }

    @Test fun limiterMustEngageBeforeAnyEqBoostAndLeaveAfterNeutralization() {
        val file = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/playback/CapsuleUnifiedDynamicsProcessor.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/playback/CapsuleUnifiedDynamicsProcessor.kt"),
        ).first(java.io.File::isFile).readText()
        val apply = file.substringAfter("fun apply(settings: EqSettings)")
            .substringBefore("fun release()")
        val enable = apply.indexOf("if (settings.enabled && appliedLimiter != true)")
        val boost = apply.indexOf("val levels = capsuleUnifiedEqLevels")
        val gain = apply.indexOf("processor.setInputGainAllChannelsTo")
        val disable = apply.indexOf("if (!settings.enabled && appliedLimiter != false)")
        assertTrue(enable >= 0 && enable < boost && boost < gain && gain < disable)
        assertTrue(file.contains(".setLimiterAllChannelsTo(nativeLimiter(true))"))
    }

    @Test fun sourceMustNotStackIndependentNativeBoostsWhenUnified() {
        val src = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/playback/PlaybackAudioEffectsController.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/playback/PlaybackAudioEffectsController.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(src.contains("unifiedDynamics?.let { engine ->"))
        assertTrue(src.contains("engine.apply(settings)"))
        // The unified branch must exit before the legacy native processors.
        val unifiedPath = src.substringAfter("unifiedDynamics?.let { engine ->")
            .substringBefore("// On API 26-27")
        assertTrue(unifiedPath.contains("return"))
        assertTrue(unifiedPath.contains("catch (failure: Exception)"))
        val service = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(service.contains("audioEffectsController.isUnifiedDynamicsActive"))
        assertTrue(service.contains("requested = enabled && !soundStudioEnabledForVolume.value"))
    }
}
