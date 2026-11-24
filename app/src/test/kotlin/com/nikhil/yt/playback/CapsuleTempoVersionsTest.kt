package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CapsuleTempoVersionsTest {
    @Test fun classicTapeEffectUsesMatchedTempoAndPitch() {
        val up=capsuleTempoParams(CAPSULE_TEMPO_SPEED_UP)
        val slow=capsuleTempoParams(CAPSULE_TEMPO_SLOWED)
        assertEquals(1.16f,up.speed,0.0001f)
        assertEquals(.85f,slow.speed,0.0001f)
        assertEquals(up.speed,up.pitch,0.0001f)
        assertEquals(slow.speed,slow.pitch,0.0001f)
        assertEquals(CapsuleTempoParams(1f,1f),capsuleTempoParams(CAPSULE_TEMPO_NORMAL))
        assertEquals(CapsuleTempoParams(1f,1f),capsuleTempoParams("unknown"))
    }

    @Test fun tempoStrengthClampedAndBounded() {
        assertEquals(capsuleTempoParams(CAPSULE_TEMPO_SPEED_UP,1000),
            capsuleTempoParams(CAPSULE_TEMPO_SPEED_UP,100))
        assertEquals(capsuleTempoParams(CAPSULE_TEMPO_SLOWED,-100),
            capsuleTempoParams(CAPSULE_TEMPO_SLOWED,0))
        assertTrue(capsuleTempoParams(CAPSULE_TEMPO_SPEED_UP,100).speed<=1.22f)
        assertTrue(capsuleTempoParams(CAPSULE_TEMPO_SLOWED,100).speed>=0.799f)
    }

    @Test fun tempoProfileDoesNotReinitializeOrResetEqualizerState() {
        val src=File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        val onlyTempo=src.substringAfter("fun selectTempoVersion(")
            .substringBefore("fun applyEqFlatPreset()")
        assertFalse(onlyTempo.contains("EqualizerEnabledKey"))
        assertFalse(onlyTempo.contains("EqualizerBandLevelsMbKey"))
        assertTrue(onlyTempo.contains("SoundStudioTempoModeKey"))
    }
}
