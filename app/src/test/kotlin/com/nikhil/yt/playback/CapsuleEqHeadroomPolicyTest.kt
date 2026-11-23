package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * Pure model tests: hardware-specific true-peak and speaker protection cannot
 * be certified from JVM, so these assert conservative numerical invariants.
 */
class CapsuleEqHeadroomPolicyTest {
    private fun settings(
        levels: List<Int> = listOf(0, 0, 0, 0, 0),
        enabled: Boolean = true,
        gain: Int = 0,
        gainEnabled: Boolean = false,
    ) = EqSettings(
        enabled = enabled, bandLevelsMb = levels, outputGainEnabled = gainEnabled,
        outputGainMb = gain, bassBoostEnabled = false, bassBoostStrength = 0,
        virtualizerEnabled = false, virtualizerStrength = 0,
    )

    private val caps = EqCapabilities(
        bandCount = 5, minBandLevelMb = -1500, maxBandLevelMb = 1500,
        centerFreqHz = listOf(60, 230, 910, 3600, 14000),
        systemPresets = emptyList(),
    )

    @Test fun boostedProfileReservesMoreThanTheLargestPositiveBand() {
        val eq = settings(listOf(0, 900, -1500, 0, 900))
        val levels = capsuleUnifiedEqLevels(eq, caps)
        assertEquals(900, levels.max())
        assertEquals(-1080, capsuleSafeEqInputGainMb(eq, levels))
        // A full-scale sample with +9 dB tonal boost stays below -1.8 dBFS
        // under a simplified single-band linear-gain model.
        val estimatedPeakDb = levels.max() / 100.0 +
            capsuleSafeEqInputGainMb(eq, levels) / 100.0
        assertTrue(estimatedPeakDb <= -1.8 + 0.00001)
        val estimatedLinearPeak = Math.pow(10.0, estimatedPeakDb / 20.0)
        assertTrue(estimatedLinearPeak < 1.0)
    }

    @Test fun bassBoostHasHeadroomToo() {
        val bass = eqSettingsWithBass()
        val levels = capsuleUnifiedEqLevels(bass, caps)
        assertEquals(250, levels.first())
        assertEquals(-430, capsuleSafeEqInputGainMb(bass, levels))
    }

    private fun eqSettingsWithBass() =
        settings().copy(bassBoostEnabled = true, bassBoostStrength = 250)

    @Test fun flatAndBypassedStatesDoNotDropMusicVolume() {
        val flat = settings()
        assertEquals(0, capsuleSafeEqInputGainMb(flat, List(5) { 0 }))
        assertEquals(0, capsuleSafeEqInputGainMb(
            settings(enabled = false, levels = List(5) { 900 }),
            List(5) { 900 },
        ))
        assertEquals(1f, protectedEqAudioFactor(flat), 0.00001f)
    }

    @Test fun negativeManualGainKeepsItsFullAdditionalAttenuation() {
        val eq = settings(listOf(0, 900, 0, 0, 0), gainEnabled = true, gain = -500)
        assertEquals(-1580, capsuleSafeEqInputGainMb(eq, listOf(0, 900, 0, 0, 0)))
    }

    @Test fun positiveMakeupCannotBypassTheEntireClippingReserve() {
        val eq = settings(listOf(0, 900, 0, 0, 0), gainEnabled = true, gain = 300)
        val input = capsuleSafeEqInputGainMb(eq, listOf(0, 900, 0, 0, 0))
        assertEquals(-990, input) // -9 -1.8 +0.9 dB; remains below full scale.
        assertTrue(input + 900 < 0)
    }

    @Test fun legacyFallbackReservesActualStrongBandBoost() {
        val high = settings(listOf(0, 1500, 900, 0, 0), enabled = true)
        val attenuation = protectedEqAudioFactor(high, caps)
        assertTrue(attenuation > 0.26f && attenuation < 0.31f)
        assertTrue(attenuation < protectedEqAudioFactor(settings(listOf(0, 400, 0, 0, 0)), caps))
    }

    @Test fun negativeOnlyEqNeedsNoUnnecessaryAttenuation() {
        val settings = settings(listOf(-400, -900, -200, 0, -500))
        assertEquals(0, capsuleSafeEqInputGainMb(settings, settings.bandLevelsMb))
        assertEquals(1f, protectedEqAudioFactor(settings, caps), 0.000001f)
    }
}
