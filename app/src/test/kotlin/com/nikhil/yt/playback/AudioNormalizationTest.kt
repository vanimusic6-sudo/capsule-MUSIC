package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioNormalizationTest {
    @Test
    fun missingOrInvalidMetadataKeepsUnityGain() {
        assertEquals(1f, calculateNormalizationFactor(null, 1.414f), 0.0001f)
        assertEquals(
            1f,
            calculateNormalizationFactor(
                TrackLoudness(Double.NaN, null),
                1.414f,
            ),
            0.0001f,
        )
    }

    @Test
    fun excessiveBoostIsCappedAtThreeDb() {
        assertEquals(
            1.414f,
            calculateNormalizationFactor(
                TrackLoudness(loudnessDb = -12.0, perceptualLoudnessDb = null),
                maxSafeGainFactor = 1.414f,
            ),
            0.0001f,
        )
    }

    @Test
    fun perceptualValueIsUsedWhenRegularLoudnessIsMissing() {
        assertEquals(
            0.5012f,
            calculateNormalizationFactor(
                TrackLoudness(loudnessDb = null, perceptualLoudnessDb = 6.0),
                maxSafeGainFactor = 1.414f,
            ),
            0.001f,
        )
    }
}
