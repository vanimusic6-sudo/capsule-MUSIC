/* Capsule MUSIC — character effect regressions. GPL-3.0. */
package com.nikhil.yt.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class CapsuleMixerCharacterTest {
    @Test fun textureAndGesturesRemainBoundedAndFollowMusic() {
        for (rate in listOf(4000, 16000, 44100, 48000, 96000, 384000)) {
            val record = CapsuleRecordTexture(rate, 2)
            val dj = CapsuleAutoDjGesture(rate, 2)
            val frame = DoubleArray(2)
            repeat(rate / 2) {
                frame.fill(0.0); record.apply(frame, 1.0); dj.apply(frame, 1.0)
                assertTrue(frame.all { it == 0.0 })
            }
            var residual = 0.0
            repeat(rate * 2) { i ->
                val x = 0.25 * sin(2 * PI * 440 * i / rate)
                frame[0] = x; frame[1] = 0.0
                record.apply(frame, 1.0)
                residual = maxOf(residual, abs(frame[0] - x))
                assertTrue(frame[1] == 0.0 && frame[0].isFinite())
                assertTrue(abs(frame[0] - x) <= 0.02400001)
            }
            assertTrue(residual > 0.0015)
            var changed = 0
            repeat(rate * 13) { i ->
                val t = (i % (rate / 2)).toDouble() / rate
                val x = 0.52 * exp(-t / 0.045) * sin(2 * PI * 75 * i / rate) +
                    0.07 * sin(2 * PI * 720 * i / rate)
                frame[0] = x; frame[1] = 0.0
                dj.apply(frame, 1.0)
                assertTrue(frame[0].isFinite() && abs(frame[0]) < 0.8 && frame[1] == 0.0)
                if (abs(frame[0] - x) > 0.005) changed++
            }
            assertTrue(dj.eventsStarted in 2..4 && changed > rate / 15)
            val steady = CapsuleAutoDjGesture(rate, 1)
            val mono = DoubleArray(1)
            repeat(rate * 5) { i ->
                mono[0] = 0.3 * sin(2 * PI * 440 * i / rate)
                steady.apply(mono, 1.0)
            }
            assertTrue(steady.eventsStarted == 0)
        }
    }
}
