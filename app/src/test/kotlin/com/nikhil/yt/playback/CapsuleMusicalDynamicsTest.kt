/* Capsule MUSIC — linked dynamic voicing regression. GPL-3.0. */
package com.nikhil.yt.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class CapsuleMusicalDynamicsTest {
    @Test fun allVoicingsRetainHeadroomAndChannelIsolationAcrossSupportedRates() {
        for (rate in listOf(4000, 16000, 44100, 48000, 96000, 384000)) {
            for (mode in CapsuleVoicing.entries) {
                for (hz in listOf(40, 100, 250, 700, 1500, 3500, 8000, 12000).filter { it < rate * 0.45 }) {
                    val dsp = CapsuleMusicalDynamics(rate, 2)
                    val frame = DoubleArray(2)
                    var dry = 0.0
                    var wet = 0.0
                    repeat(rate / 2) { i ->
                        val x = 0.35 * sin(2 * PI * hz * i / rate)
                        frame[0] = x; frame[1] = -x
                        dsp.apply(frame, mode, 1.0)
                        assertTrue(frame.all { it.isFinite() })
                        assertTrue(abs(frame[0] + frame[1]) < 1e-12)
                        if (i >= rate / 4) { dry += x * x; wet += frame[0] * frame[0] }
                    }
                    assertTrue(10 * log10(wet / dry) in -7.0..0.15)
                }
                val dsp = CapsuleMusicalDynamics(rate, 2)
                val frame = DoubleArray(2)
                repeat(rate / 4) { i ->
                    frame[0] = if (i % 2 == 0) 1.0 else -1.0; frame[1] = 0.0
                    dsp.apply(frame, mode, 1.0)
                    assertTrue(frame[0].isFinite() && abs(frame[0]) <= 1.5 && frame[1] == 0.0)
                }
                repeat(rate / 8) { frame.fill(0.0); dsp.apply(frame, null, 0.0) }
                assertTrue(!dsp.active)
            }
        }
    }

    @Test fun slowedRoomDoesNotFeedBackOrLeakBetweenChannels() {
        val rate = 48000
        val stage = CapsuleVersionStage(rate, 2)
        val frame = DoubleArray(2)
        repeat(rate) { i ->
            frame[0] = 0.7 * sin(2 * PI * 440 * i / rate); frame[1] = 0.0
            stage.apply(frame, CapsuleVoicing.SLOW, 0.3)
            assertTrue(frame[0].isFinite() && abs(frame[0]) <= 1.0 && frame[1] == 0.0)
        }
        repeat(rate / 2) { frame.fill(0.0); stage.apply(frame, null, 0.3) }
        assertTrue(!stage.active)
    }
}
