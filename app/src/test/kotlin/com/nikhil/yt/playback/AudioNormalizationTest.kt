package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioNormalizationTest {
    @Test
    fun missingOrInvalidMetadataKeepsUnityGain() {
        assertEquals(1f, calculateNormalizationFactor(null, 1.414f), 0.0001f)
        assertEquals(
            1f,
            calculateNormalizationFactor(TrackLoudness(Double.NaN, null), 1.414f),
            0.0001f,
        )
    }

    @Test
    fun rawYoutubeLoudnessUsesMinusSevenLufsReference() {
        assertEquals(-12.0, measuredLoudnessLufs(-5.0, null)!!, 0.0001)
        assertEquals(-7.0, measuredLoudnessLufs(0.0, null)!!, 0.0001)
    }

    @Test
    fun perceptualMeasurementWinsOverLegacyRawValue() {
        assertEquals(-16.0, measuredLoudnessLufs(-5.0, -16.0)!!, 0.0001)
    }

    @Test
    fun balancedTargetAttenuatesTypicalLegacyYoutubeValue() {
        val factor = calculateNormalizationFactor(TrackLoudness(-5.0, null), 1.414f)
        assertEquals(0.7943282f, factor, 0.0001f)
    }

    @Test
    fun perceptualQuietTrackGetsModerateGain() {
        val factor = calculateNormalizationFactor(TrackLoudness(null, -16.0), 1.414f)
        assertEquals(1.2589254f, factor, 0.0001f)
    }

    @Test
    fun excessiveBoostStillUsesExistingThreeDbCeiling() {
        val factor = calculateNormalizationFactor(TrackLoudness(null, -30.0), 1.414f)
        assertEquals(1.414f, factor, 0.0001f)
    }

    @Test
    fun attenuationIsLimitedToTwelveDb() {
        val factor = calculateNormalizationFactor(TrackLoudness(null, 0.0), 1.414f)
        assertEquals(0.2511886f, factor, 0.0001f)
    }

    @Test
    fun rawZeroIsNotSilence() {
        val factor = calculateNormalizationFactor(TrackLoudness(0.0, null), 1.414f)
        assertEquals(0.4466836f, factor, 0.0001f)
    }

    @Test
    fun offloadIsDisabledWhileCrossfadeIsActive() {
        assertTrue(shouldEnableAudioOffload(requested = true, crossfadeDurationMs = 0))
        assertFalse(shouldEnableAudioOffload(requested = true, crossfadeDurationMs = 1_000))
        assertFalse(shouldEnableAudioOffload(requested = false, crossfadeDurationMs = 0))
    }
}
