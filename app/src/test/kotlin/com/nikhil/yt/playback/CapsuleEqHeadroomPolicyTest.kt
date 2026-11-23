package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.pow

/** Headroom arithmetic is not calibrated true-peak or SPL certification. */
class CapsuleEqHeadroomPolicyTest {
    private val hz = listOf(60,230,910,3600,14000)
    private val caps = EqCapabilities(5,-1500,1500,hz,emptyList())
    private fun settings(levels: List<Int> = List(5) {0},
        boost: Int=0, outputGain: Int=0, gainOn: Boolean=false,
        enabled:Boolean=true) = EqSettings(
        enabled=enabled,bandLevelsMb=levels,outputGainEnabled=gainOn,
        outputGainMb=outputGain,bassBoostEnabled=boost>0,
        bassBoostStrength=boost,virtualizerEnabled=false,virtualizerStrength=0,
    )

    @Test fun tonalPeakAlwaysHasCleanNegativeInputHeadroom() {
        val eq = settings(listOf(1500,1500,-1500,1200,1500))
        val levels = capsuleUnifiedEqLevels(eq,caps)
        val trim = capsuleSafeEqInputGainMb(eq,levels)
        val positive = levels.max().coerceAtLeast(0)
        assertTrue(positive <= CAPSULE_MAX_EQ_BOOST_MB)
        assertTrue(trim+positive <= -CAPSULE_EQ_HEADROOM_MARGIN_MB)
        assertTrue(10.0.pow((trim+positive)/2000.0) < 1.0)
    }

    @Test fun bassIsAShapedContourAndNeverIndependentCompression() {
        val bass = settings(boost=200)
        val levels = capsuleUnifiedEqLevels(bass,caps)
        assertTrue(levels.first() in 1..CAPSULE_MAX_LOW_BASS_BOOST_MB)
        assertTrue(levels.drop(2).all { it <= 50 })
        assertFalse(capsuleNeedsBassDynamics(bass,caps))
        val trim = capsuleSafeEqInputGainMb(bass,levels)
        assertTrue(trim + levels.max() <= -CAPSULE_EQ_HEADROOM_MARGIN_MB)
    }

    @Test fun positiveOutputGainIsNeverRecoveredFromTheLimiterReserve() {
        val eq = settings(listOf(0,1500,0,0,0),outputGain=300,gainOn=true)
        val levels = capsuleUnifiedEqLevels(eq,caps)
        val requested = capsuleSafeEqInputGainMb(eq,levels)
        assertEquals(capsuleSafeEqInputGainMb(eq.copy(outputGainEnabled=false),levels),requested)
        assertTrue(requested+levels.max() <= -CAPSULE_EQ_HEADROOM_MARGIN_MB)
    }

    @Test fun reductionOnlyWorksWithoutLoudnessOrFlatCurveSurprises() {
        val flat = settings()
        assertEquals(0,capsuleSafeEqInputGainMb(flat,List(5){0}))
        assertEquals(1f,protectedEqAudioFactor(flat,caps),0.000001f)
        val lower = flat.copy(outputGainEnabled=true,outputGainMb=-500)
        assertEquals(-500,capsuleSafeEqInputGainMb(lower,List(5){0}))
        assertEquals(0,capsuleSafeEqInputGainMb(flat.copy(enabled=false),List(5){300}))
    }

    @Test fun nativeFallbackAppliesTheExactPreampWithNoMakeupBoost() {
        val eq=settings(listOf(0,500,-1000,200,500))
        val shaped=capsuleUnifiedEqLevels(eq,caps)
        val db=capsuleSafeEqInputGainMb(eq,shaped)
        val expected=10.0.pow(db/2000.0).toFloat()
        assertEquals(expected,protectedEqAudioFactor(eq,caps),0.000001f)
        assertTrue(expected in 0f..1f)
    }

    @Test fun normalizationIsNotRepeatedOrUsedToDefeatHeadroom() {
        assertEquals(0.75f,protectedEqPlaybackVolume(1f,2f,.75f,1f,true),0.000001f)
        assertEquals(.5f,protectedEqPlaybackVolume(.5f,.5f,1f,1f,true),0.000001f)
        assertEquals(0f,protectedEqPlaybackVolume(Float.NaN,1f,1f,1f,true),0.000001f)
        assertEquals(0f,protectedEqPlaybackVolume(1f,Float.POSITIVE_INFINITY,1f,1f,true),0.000001f)
        assertEquals(1f,protectedEqPlaybackVolume(4f,1f,1f,1f,false),0.000001f)
    }

    @Test fun serviceRoutesVolumeThroughHearingGuardAndPerEngineHeadroom() {
        val service=File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        assertTrue(service.contains("protectedEqAudioFactor(settings, eqCapabilities.value)"))
        assertTrue(service.contains("protectedEqPlaybackVolume("))
        assertTrue(service.contains("hearingCareOutputVolume(protected, ceiling)"))
        assertTrue(service.contains("audioEffectsController.isUnifiedDynamicsActive -> 1f"))
        assertTrue(service.contains("videoCompanion?.setVolume(hearingCareOutputVolume(base, ceiling))"))
    }
}
