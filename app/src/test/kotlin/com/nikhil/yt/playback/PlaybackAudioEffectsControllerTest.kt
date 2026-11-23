package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlaybackAudioEffectsControllerTest {
    @Test fun resamplesToEverySupportedHardwareBandCount() {
        assertEquals(emptyList<Int>(),resampleEqLevelsByIndex(listOf(1,2),0))
        assertEquals(emptyList<Int>(),resampleEqLevelsByIndex(listOf(1,2),-1))
        assertEquals(listOf(0,0,0),resampleEqLevelsByIndex(emptyList(),3))
        assertEquals(listOf(-100,0,100),resampleEqLevelsByIndex(listOf(-100,0,100),3))
        assertEquals(listOf(0),resampleEqLevelsByIndex(listOf(-300,0,300),1))
        assertEquals(listOf(0,500,1000,1500,2000),
            resampleEqLevelsByIndex(listOf(0,1000,2000),5))
    }

    @Test fun malformedUserIntensityCannotBypassDeviceBounds() {
        assertEquals(listOf(-1500,0,1500),
            scaleEqBandLevels(listOf(-1200,0,1200),200,-1500,1500))
        assertEquals(250,inverseScaledEqLevel(500,200,-1500,1500))
        assertEquals(-250,inverseScaledEqLevel(-500,200,-1500,1500))
    }

    @Test fun oldProfilesAreClampedByThePermanentToneGuard() {
        val eq=EqSettings(true,listOf(1400,800,-200,-500,900),
            true,1500,true,1000,true,1000,200,false)
        val effective=protectedEqLevels(eq,5,-1500,1500,
            listOf(60,230,910,3600,14000))
        assertTrue(effective.first()<=CAPSULE_MAX_LOW_BASS_BOOST_MB)
        assertTrue(effective.all{it in -420..CAPSULE_MAX_EQ_BOOST_MB})
        assertTrue(capsuleEqCurveSpreadMb(effective)<=CAPSULE_MUSICAL_MAX_SPREAD_MB)
        assertEquals(List(5){0},protectedEqLevels(eq.copy(enabled=false),5,-1500,1500))
        assertEquals(List(5){0},protectedEqLevels(eq,5,100,-100))
        assertEquals(emptyList<Int>(),protectedEqLevels(eq,0,-1500,1500))
    }

    @Test fun malformedImportOrInterpolationCannotOverflowOrAllocateUnboundedCurves() {
        assertEquals(emptyList<Int>(), decodeEqBandLevels("[" + "1,".repeat(9000) + "0]"))
        assertEquals(listOf(1500,-1500), decodeEqBandLevels("[2147483647,-2147483648]"))
        assertEquals(64, decodeEqBandLevels("[" + List(256) { "100" }.joinToString(",") + "]").size)
        assertEquals(listOf(0), resampleEqLevelsByIndex(listOf(Int.MIN_VALUE, Int.MAX_VALUE),1))
        val interpolated = resampleEqLevelsByIndex(listOf(Int.MIN_VALUE, Int.MAX_VALUE),3)
        assertEquals(3, interpolated.size)
        assertTrue(interpolated[1] in -10..10)
        assertEquals(32, resampleEqLevelsByIndex(listOf(1,2),1024).size)
    }

    @Test fun aNativeFallbackNeverActivatesAnExtraLoudnessEngine() {
        val source=File("src/main/kotlin/com/nikhil/yt/playback/PlaybackAudioEffectsController.kt").readText()
        assertFalse(source.contains("LoudnessEnhancer("))
        assertFalse(source.contains("BassBoost("))
        assertFalse(source.contains("Virtualizer("))
        assertFalse(source.contains("eq.usePreset("))
        assertFalse(source.contains("eq.enabled = false"))
        assertTrue(source.contains("appliedEnabled == true"))
    }
}
