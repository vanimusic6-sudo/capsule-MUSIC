/* Capsule MUSIC — safety regression. GPL-3.0. */
package com.nikhil.yt.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class CapsuleStudioSafetyTest {
    @Test fun recordAndRelativeLoadGuardRemainBoundedAtSupportedRates() {
        for (rate in listOf(4000, 16000, 44100, 48000, 96000, 384000)) {
            val record = CapsuleRecordVoicing(rate, 2)
            val frame = DoubleArray(2)
            repeat(rate / 2) { i ->
                frame[0] = if (i % 7 < 3) 1.0 else -1.0
                frame[1] = 0.0
                record.apply(frame, 1.0)
                assertTrue(frame[0].isFinite() && abs(frame[0]) <= 1.5 && frame[1] == 0.0)
            }
            val neutral = doubleArrayOf(0.3, -0.2)
            record.apply(neutral, 0.0)
            assertTrue(neutral.contentEquals(doubleArrayOf(0.3, -0.2)))
            for (frequency in listOf(50, minOf(8000, rate / 3))) {
                val guard = CapsuleStudioLoadGuard(rate, 2)
                val dry = DoubleArray(2)
                val wet = DoubleArray(2)
                var gain = 1.0
                repeat(rate * 2) { i ->
                    dry[0] = 0.1 * sin(2 * PI * frequency * i / rate)
                    wet[0] = 4 * dry[0]
                    gain = guard.observe(dry, wet, 1.0)
                    assertTrue(gain in 0.0..1.0)
                }
                assertTrue("Excess band energy at $rate/$frequency", gain < 0.4)
                repeat(rate * 3) { i ->
                    dry[0] = 0.1 * sin(2 * PI * frequency * i / rate)
                    wet[0] = dry[0]
                    gain = guard.observe(dry, wet, 0.0)
                }
                assertTrue(gain > 0.99)
            }
        }
    }
    @Test fun loudChannelCannotMaskExcessEnergyInAnotherChannel() {
        val rate = 48000
        for (hz in listOf(50, 8000)) {
            val guard = CapsuleStudioLoadGuard(rate, 2)
            val dry = DoubleArray(2)
            val wet = DoubleArray(2)
            var gain = 1.0
            repeat(rate * 3) { i ->
                val x = sin(2 * PI * hz * i / rate)
                dry[0] = 0.65 * x
                wet[0] = dry[0]
                dry[1] = 0.10 * x
                wet[1] = 0.40 * x
                gain = guard.observe(dry, wet, 1.0)
            }
            assertTrue("Hidden excess at $hz Hz: $gain", gain < 0.4)
        }
    }

    @Test fun sustainedDcIsAttenuatedWithoutTouchingOrdinaryBass() {
        val rate = 48000
        val guard = CapsuleStudioLoadGuard(rate, 1)
        val dry = doubleArrayOf(0.4)
        val wet = doubleArrayOf(0.4)
        var gain = 1.0
        repeat(rate * 3) { gain = guard.observe(dry, wet, 1.0) }
        assertTrue(gain < 0.15)
        val ordinary = CapsuleStudioLoadGuard(rate, 1)
        repeat(rate * 2) { i ->
            dry[0] = 0.4 * sin(2 * PI * 60 * i / rate)
            wet[0] = dry[0]
            gain = ordinary.observe(dry, wet, 1.0)
        }
        assertTrue(gain > 0.99)
    }
}
