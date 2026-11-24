/* Capsule MUSIC — PCM tonal analysis. GPL-3.0. */
package com.nikhil.yt.playback

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp

/** Chooses one voicing after two agreeing windows. Silence and transients cannot set the mode. */
internal class CapsuleStudioAnalysis(sampleRate: Int, private val channels: Int) {
    private val low = DoubleArray(channels)
    private val highBase = DoubleArray(channels)
    private val lowStep = 1.0 - exp(-2.0 * PI * 165 / sampleRate)
    private val highStep = 1.0 - exp(-2.0 * PI * 2_300 / sampleRate)
    private val window = sampleRate * 5 / 4
    private var frames = 0
    private var activeFrames = 0
    private var total = 0.0
    private var bass = 0.0
    private var treble = 0.0
    private var previous: CapsuleEqPresets? = null
    private var audibleWindows = 0

    fun observe(input: ByteBuffer): CapsuleEqPresets? {
        val frameBytes = channels * Short.SIZE_BYTES
        var index = input.position()
        while (index + frameBytes <= input.limit()) {
            var framePower = 0.0
            for (channel in 0 until channels) {
                val x = input.getShort(index) / 32768.0
                index += Short.SIZE_BYTES
                low[channel] += lowStep * (x - low[channel])
                highBase[channel] += highStep * (x - highBase[channel])
                val high = x - highBase[channel]
                framePower += x * x
                bass += low[channel] * low[channel]
                treble += high * high
            }
            total += framePower
            if (framePower > 0.000025 * channels) activeFrames++
            frames++
            if (frames != window) continue
            val candidate = if (activeFrames < window / 5) null else when {
                bass / total > 0.56 -> CapsuleEqPresets.CLEAR
                treble / total > 0.46 -> CapsuleEqPresets.TAPE
                else -> CapsuleEqPresets.WARM
            }
            frames = 0
            activeFrames = 0
            total = 0.0
            bass = 0.0
            treble = 0.0
            if (candidate != null) {
                if (candidate == previous) return candidate
                if (++audibleWindows == 5) return CapsuleEqPresets.WARM
            }
            previous = candidate
        }
        return null
    }
}
