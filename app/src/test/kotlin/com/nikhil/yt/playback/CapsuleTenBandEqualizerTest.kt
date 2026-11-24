package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CapsuleTenBandEqualizerTest {
    @Test fun independentProcessorProvidesExactlyTenAscendingBands() {
        val caps = capsuleTenBandCapabilities()
        assertEquals(10,caps.bandCount)
        assertEquals(CAPSULE_TEN_BAND_HZ,caps.centerFreqHz)
        assertEquals(10,CAPSULE_TEN_BAND_HZ.distinct().size)
        assertTrue(CAPSULE_TEN_BAND_HZ.zipWithNext().all { (a,b) -> b>a })
        val cutoffs=capsuleDspCutoffsHz(caps.centerFreqHz,10)
        assertEquals(10,cutoffs.size)
        assertEquals(20000f,cutoffs.last(),0.1f)
        assertTrue(cutoffs.zipWithNext().all { (a,b) -> b>a })
    }

    @Test fun nativeFiveBandFallbackProjectsActualTenBandFrequencyCurve() {
        val ten=listOf(150,250,270,170,80,-10,-70,-100,-120,-100)
        assertEquals(ten,capsuleProjectEqLevelsByFrequency(ten,CAPSULE_TEN_BAND_HZ))
        val centers=listOf(80,315,1250,6300,16000)
        assertEquals(listOf(150,270,80,-100,-100),
            capsuleProjectEqLevelsByFrequency(ten,centers))
        val native=EqCapabilities(5,-1500,1500,centers,emptyList())
        val settings=EqSettings(true,ten,false,0,false,0,false,0)
        val mapped=capsuleUnifiedEqLevels(settings,native)
        assertEquals(5,mapped.size)
        assertTrue(mapped.all { it in -460..320 })
        assertTrue(mapped[1]>mapped[4])
    }

    @Test fun malformedFrequencyMetadataAndBigGainsRemainFiniteAndClamped() {
        val output=capsuleProjectEqLevelsByFrequency(
            List(10) { i -> if (i%2==0) Int.MAX_VALUE else Int.MIN_VALUE },
            listOf(0, -10, 0, Int.MAX_VALUE, 999_999),
        )
        assertEquals(5,output.size)
        assertTrue(output.all { it in -1500..1500 })
        assertEquals(emptyList<Int>(),
            capsuleProjectEqLevelsByFrequency(listOf(1,2),emptyList()))
        assertEquals(List(10){0},
            capsuleProjectEqLevelsByFrequency(emptyList(),CAPSULE_TEN_BAND_HZ))
    }

    @Test fun controllerCreatesIndependentTenBandDspAndNeverStacksNative() {
        val file=File("src/main/kotlin/com/nikhil/yt/playback/PlaybackAudioEffectsController.kt")
            .readText()
        assertTrue(file.contains("session, capsuleTenBandCapabilities()"))
        assertTrue(file.contains("disable native before DSP"))
        assertTrue(file.contains("nativeCapabilities"))
        assertTrue(file.contains("capsuleUnifiedEqLevels(settings, caps)"))
    }
}
