/* Capsule MUSIC — cassette coloration and transport. GPL-3.0. */
package com.nikhil.yt.playback

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

private const val TAPE_PHASE_COUNT = 1024
private val tapeSine = DoubleArray(TAPE_PHASE_COUNT + 1) { sin(2.0 * PI * it / TAPE_PHASE_COUNT) }

/** A shared transport for all channels, with independent magnetic drive per channel. */
internal class CapsuleTapeTransport(sampleRate: Int, private val channels: Int) : MixerEffect {
    private val delayFrames = sampleRate * 0.006
    private val wowDepth = sampleRate * 0.00135
    private val driftDepth = sampleRate * 0.00105
    private val flutterDepth = sampleRate * 0.00019
    private val wowStep = 0.76 * TAPE_PHASE_COUNT / sampleRate
    private val driftStep = 0.29 * TAPE_PHASE_COUNT / sampleRate
    private val flutterStep = 6.4 * TAPE_PHASE_COUNT / sampleRate
    private val bassStep = tapeFilterStep(170.0, sampleRate)
    private val driveStep = tapeFilterStep(2_900.0, sampleRate)
    private val offsetStep = tapeFilterStep(34.0, sampleRate)
    private val headStep = tapeFilterStep(5_400.0, sampleRate)
    private val attack = 1.0 - exp(-1.0 / (sampleRate * 0.009))
    private val release = 1.0 - exp(-1.0 / (sampleRate * 0.092))
    private val capacity = Integer.highestOneBit(sampleRate / 80) shl 1
    private val ring = FloatArray(capacity * channels)
    private val bass = DoubleArray(channels)
    private val drive = DoubleArray(channels)
    private val offset = DoubleArray(channels)
    private val head = DoubleArray(channels)
    private val playbackHead = DoubleArray(channels)
    private var write = 0
    private var written = 0
    private var wow = 0.0
    private var drift = TAPE_PHASE_COUNT * 0.37
    private var flutter = TAPE_PHASE_COUNT * 0.31
    private var envelope = 0.0
    private var readOffset = 0
    private var nextOffset = 0
    private var fraction = 0.0
    private var blend = 0.0

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        envelope += (peak - envelope) * if (peak > envelope) attack else release
        val compression = 1.0 - amount * 0.16 * ((envelope - 0.22) / 0.53).coerceIn(0.0, 1.0)
        for (channel in frame.indices) frame[channel] = process(frame[channel] * compression, channel, amount, transition)
    }

    private fun process(sample: Double, channel: Int, amount: Double, transition: Double): Double {
        if (channel == 0) {
            // Keep the nominal delay steady so changing strength does not bend pitch.
            val slow = sinusoid(drift)
            val delay = delayFrames + amount * (wowDepth * sinusoid(wow) + driftDepth * slow +
                flutterDepth * sinusoid(flutter) * (0.76 + 0.24 * slow))
            val position = write - minOf(written.toDouble(), delay)
            val read = floor(position).toInt()
            readOffset = (read and (capacity - 1)) * channels
            nextOffset = ((read + 1) and (capacity - 1)) * channels
            fraction = position - read
            // Only fade to the delayed tap after it has real samples.
            blend = ((written - delay) / delayFrames).coerceIn(0.0, 1.0)
            wow = wrapPhase(wow + wowStep)
            drift = wrapPhase(drift + driftStep)
            flutter = wrapPhase(flutter + flutterStep)
        }
        // Excite the record stage with the mids. Sub-bass stays out of the nonlinear loop.
        bass[channel] += bassStep * (sample - bass[channel])
        drive[channel] += driveStep * (sample - bass[channel] - drive[channel])
        val band = drive[channel]
        val magnetic = band * (capsuleTapeGain(band * 4.1 + 0.075) - 1.0)
        offset[channel] += offsetStep * (magnetic - offset[channel])
        val saturated = sample + amount * (magnetic - offset[channel])
        head[channel] += headStep * (saturated - head[channel])
        playbackHead[channel] += headStep * (head[channel] - playbackHead[channel])
        val recorded = saturated + amount * 0.82 * (playbackHead[channel] - saturated)
        ring[write * channels + channel] = recorded.toFloat()
        val first = ring[readOffset + channel].toDouble()
        val second = ring[nextOffset + channel].toDouble()
        val delayed = first + (second - first) * fraction
        if (channel == channels - 1) {
            write = (write + 1) and (capacity - 1)
            if (written < capacity * 2) written++
        }
        return recorded + (delayed - recorded) * blend * transition
    }

    private fun sinusoid(phase: Double): Double {
        val index = phase.toInt()
        return tapeSine[index] + (tapeSine[index + 1] - tapeSine[index]) * (phase - index)
    }

    private fun wrapPhase(phase: Double): Double = if (phase >= TAPE_PHASE_COUNT) phase - TAPE_PHASE_COUNT else phase
}

private fun tapeFilterStep(hz: Double, sampleRate: Int): Double =
    1.0 - exp(-2.0 * PI * minOf(hz, sampleRate * 0.28) / sampleRate)

/** Soft rounding of peaks with a linear response near zero and no makeup gain. */
internal fun capsuleTapeGain(drive: Double): Double {
    val power = drive * drive
    return 1.0 - 0.37 * power / (0.16 + power)
}
