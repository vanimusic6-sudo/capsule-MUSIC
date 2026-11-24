/* Capsule MUSIC — cassette coloration and transport. GPL-3.0. */
package com.nikhil.yt.playback

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

private const val TAPE_PHASE_COUNT = 1024
private val tapeSine = DoubleArray(TAPE_PHASE_COUNT + 1) { sin(2.0 * PI * it / TAPE_PHASE_COUNT) }

/** A shared transport for all channels, with independent magnetic drive per channel. */
internal class CapsuleTapeTransport(sampleRate: Int, private val channels: Int) {
    private val delayFrames = sampleRate * 0.006
    private val wowDepth = sampleRate * 0.00135
    private val flutterDepth = sampleRate * 0.00019
    private val wowStep = 0.76 * TAPE_PHASE_COUNT / sampleRate
    private val flutterStep = 6.4 * TAPE_PHASE_COUNT / sampleRate
    private val driveStep = 1.0 - exp(-2.0 * PI * minOf(1_750.0, sampleRate / 8.0) / sampleRate)
    private val headStep = 1.0 - exp(-2.0 * PI * minOf(6_400.0, sampleRate * 0.28) / sampleRate)
    private val capacity = Integer.highestOneBit(sampleRate / 80) shl 1
    private val ring = FloatArray(capacity * channels)
    private val drive = DoubleArray(channels)
    private val head = DoubleArray(channels)
    private var write = 0
    private var written = 0
    private var wow = 0.0
    private var flutter = TAPE_PHASE_COUNT * 0.31
    private var readOffset = 0
    private var nextOffset = 0
    private var fraction = 0.0
    private var blend = 0.0

    fun process(sample: Double, channel: Int): Double {
        if (channel == 0) {
            // Delay modulation changes pitch in both directions around unity.
            val position = write - delayFrames - wowDepth * sinusoid(wow) - flutterDepth * sinusoid(flutter)
            val read = floor(position).toInt()
            readOffset = (read and (capacity - 1)) * channels
            nextOffset = ((read + 1) and (capacity - 1)) * channels
            fraction = position - read
            // Only fade to the delayed tap after it has real samples.
            blend = ((written - delayFrames - wowDepth - flutterDepth) / delayFrames).coerceIn(0.0, 1.0)
            wow = wrapPhase(wow + wowStep)
            flutter = wrapPhase(flutter + flutterStep)
        }
        drive[channel] += driveStep * (sample - drive[channel])
        val saturated = sample * capsuleTapeGain(drive[channel])
        head[channel] += headStep * (saturated - head[channel])
        val recorded = head[channel]
        ring[write * channels + channel] = recorded.toFloat()
        val first = ring[readOffset + channel].toDouble()
        val second = ring[nextOffset + channel].toDouble()
        val delayed = first + (second - first) * fraction
        if (channel == channels - 1) {
            write = (write + 1) and (capacity - 1)
            if (written < capacity * 2) written++
        }
        return recorded + (delayed - recorded) * blend
    }

    private fun sinusoid(phase: Double): Double {
        val index = phase.toInt()
        return tapeSine[index] + (tapeSine[index + 1] - tapeSine[index]) * (phase - index)
    }

    private fun wrapPhase(phase: Double): Double = if (phase >= TAPE_PHASE_COUNT) phase - TAPE_PHASE_COUNT else phase
}

/** Soft rounding of peaks with a linear response near zero and no makeup gain. */
internal fun capsuleTapeGain(drive: Double): Double {
    val power = drive * drive
    return 1.0 - 0.32 * power / (0.16 + power)
}
