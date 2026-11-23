package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CapsuleUnifiedDynamicsProcessorTest {
    private val caps=EqCapabilities(5,-1500,1500,
        listOf(60,230,910,3600,14000),emptyList())
    private fun settings(levels:List<Int> = List(5){0},bass:Int=0,enabled:Boolean=true) =
        EqSettings(enabled,levels,false,0,bass>0,bass,false,0)

    @Test fun frequencyCutoffsRemainMonotonicAcrossOEMMetadata() {
        val cutoffs=capsuleDspCutoffsHz(caps.centerFreqHz,5)
        assertEquals(5,cutoffs.size)
        assertEquals(20000f,cutoffs.last(),0.01f)
        assertTrue(cutoffs.first() in 60f..230f)
        assertTrue(cutoffs.zipWithNext().all{ (a,b) -> b > a })
        val bad=capsuleDspCutoffsHz(listOf(0,-99,2,2,Int.MAX_VALUE),5)
        assertTrue(bad.zipWithNext().all{(a,b)->b>a})
        assertEquals(32,capsuleDspCutoffsHz(emptyList(),40).size)
        assertEquals(emptyList<Float>(),capsuleDspCutoffsHz(emptyList(),0))
    }

    @Test fun engineContainsOnlyPreEqAndFinalLimiter() {
        val s=File("src/main/kotlin/com/nikhil/yt/playback/CapsuleUnifiedDynamicsProcessor.kt").readText()
        assertTrue(s.contains(".setPreEqAllChannelsTo(eq)"))
        assertTrue(s.contains(".setLimiterAllChannelsTo(nativeLimiter())"))
        assertFalse(s.contains("setMbcAllChannelsTo"))
        assertFalse(s.contains("setPostEqAllChannelsTo"))
        assertFalse(s.contains("setLimiterAllChannelsTo(nativeLimiter(false))"))
        val apply=s.substringAfter("fun apply(settings: EqSettings)")
            .substringBefore("fun release()")
        val down=apply.indexOf("if (appliedInputGain == null || preamp < appliedInputGain!!)")
        val pre=apply.indexOf("effect.setInputGainAllChannelsTo")
        val eq=apply.indexOf("effect.setPreEqBandAllChannelsTo")
        assertTrue(down>=0 && down<pre && pre<eq)
    }

    @Test fun bassIsBoundedAndNoMbcCanBeActivated() {
        val s=settings(listOf(1500,1500,0,0,0),200)
        val shaped=capsuleUnifiedEqLevels(s,caps)
        assertEquals(5,shaped.size)
        assertTrue(shaped.first() <= 220)
        assertTrue(shaped.all{it<=350})
        assertTrue(capsuleEqCurveSpreadMb(shaped) <= 700)
        assertFalse(capsuleNeedsBassDynamics(s,caps))
        assertEquals(List(5){0},capsuleUnifiedEqLevels(s.copy(enabled=false),caps))
    }

    @Test fun controllerNeverStacksIndependentBoostsOrEnablesGlobalSession() {
        val s=File("src/main/kotlin/com/nikhil/yt/playback/PlaybackAudioEffectsController.kt").readText()
        assertTrue(s.contains("unifiedDynamics?.let { engine ->"))
        assertTrue(s.contains("engine.apply(settings)"))
        assertFalse(s.contains("BassBoost("))
        assertFalse(s.contains("Virtualizer("))
        assertFalse(s.contains("LoudnessEnhancer("))
        assertFalse(s.contains("DynamicsProcessing.Config.Builder"))
        assertTrue(s.contains("if (sessionId <= 0 || this.sessionId == sessionId) return"))
        assertTrue(s.contains("capsuleUnifiedEqLevels(settings, caps)"))
    }
}
