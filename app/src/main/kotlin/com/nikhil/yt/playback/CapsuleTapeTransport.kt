/* Capsule MUSIC — tape transport rate and record coloration. GPL-3.0. */
package com.nikhil.yt.playback

import kotlin.math.PI
import kotlin.math.exp

/** Keep the source clock and pitch coupled, including when combined with a tempo variant. */
internal fun capsuleTapeRate(base: Float, variant: SoundStudioTempoVariant, mixer: CapsuleMixerSetting): Float {
    if (mixer.effect != CapsuleMixerEffect.TAPE || !mixer.enabled) return base
    val amount = mixer.amount.coerceIn(0, 100) * 0.01f
    val floor = when (variant) {
        SoundStudioTempoVariant.NORMAL -> 0.91f
        SoundStudioTempoVariant.SPEED_UP -> 1.018f
        SoundStudioTempoVariant.SLOWED -> 0.875f
    }
    return (base * (1f - 0.085f * amount)).coerceAtLeast(floor)
}

/** Gentle recording compression and upper-mid saturation. Playback speed is set by the player. */
internal class CapsuleTapeTransport(sampleRate: Int, channels: Int) : MixerEffect {
    private val low = DoubleArray(channels)
    private val record = DoubleArray(channels)
    private val offset = DoubleArray(channels)
    private val head = DoubleArray(channels)
    private val lowStep = coefficient(140.0, sampleRate)
    private val recordStep = coefficient(2_850.0, sampleRate)
    private val offsetStep = coefficient(36.0, sampleRate)
    private val headStep = coefficient(5_050.0, sampleRate)
    private val attack = 1.0 - exp(-1.0 / (sampleRate * 0.011))
    private val release = 1.0 - exp(-1.0 / (sampleRate * 0.12))
    private var envelope = 0.0

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        envelope += (peak - envelope) * if (peak > envelope) attack else release
        val compression = 1.0 - amount * 0.15 * ((envelope - 0.22) / 0.55).coerceIn(0.0, 1.0)
        for (channel in frame.indices) {
            val x = frame[channel] * compression
            low[channel] += (x - low[channel]) * lowStep
            record[channel] += (x - low[channel] - record[channel]) * recordStep
            val band = record[channel]
            val magnetic = band * (capsuleTapeGain(band * 3.1 + 0.07) - 1.0)
            offset[channel] += (magnetic - offset[channel]) * offsetStep
            val recorded = x + amount * (magnetic - offset[channel])
            head[channel] += (recorded - head[channel]) * headStep
            frame[channel] = recorded + (head[channel] - recorded) * (0.66 * amount)
        }
    }

    private fun coefficient(hz: Double, sampleRate: Int): Double =
        1.0 - exp(-2.0 * PI * minOf(hz, sampleRate * 0.28) / sampleRate)
}

/** Soft rounding of peaks with a linear response near zero and no makeup gain. */
internal fun capsuleTapeGain(drive: Double): Double {
    val power = drive * drive
    return 1.0 - 0.37 * power / (0.16 + power)
}
