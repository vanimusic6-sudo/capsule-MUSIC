/* Capsule MUSIC — independent PCM effects. GPL-3.0. */
package com.nikhil.yt.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

internal enum class CapsuleMixerEffect(val id: String, val defaultAmount: Int) {
    OFF("off", 0),
    TAPE("tape", 72),
    SOFT("soft", 75),
    SUN("sun", 65),
    VINYL("vinyl", 55),
}

internal data class CapsuleMixerSetting(val effect: CapsuleMixerEffect = CapsuleMixerEffect.OFF, val amount: Int = 0) {
    val enabled: Boolean get() = effect != CapsuleMixerEffect.OFF && amount > 0
}

internal data class CapsuleMixerProfile(
    val effect: CapsuleMixerEffect = CapsuleMixerEffect.OFF,
    private val amounts: List<Int> = CapsuleMixerEffect.entries.drop(1).map { it.defaultAmount },
) {
    val setting: CapsuleMixerSetting
        get() = CapsuleMixerSetting(effect, if (effect == CapsuleMixerEffect.OFF) 0 else amounts[effect.ordinal - 1])

    fun withAmount(effect: CapsuleMixerEffect, amount: Int): CapsuleMixerProfile {
        if (effect == CapsuleMixerEffect.OFF) return copy(effect = effect)
        return copy(effect = effect, amounts = amounts.mapIndexed { index, value ->
            if (index == effect.ordinal - 1) amount.coerceIn(0, 100) else value
        })
    }

    fun encode(): String = "${effect.id}:${amounts.joinToString(",")}"

    companion object {
        fun resolve(raw: String?, legacyEq: String?, eqEnabled: Boolean): CapsuleMixerProfile {
            if (raw.isNullOrEmpty()) {
                val migrated = eqEnabled && CapsuleEqPresets.byId(legacyEq.orEmpty()) == CapsuleEqPresets.TAPE
                return CapsuleMixerProfile(if (migrated) CapsuleMixerEffect.TAPE else CapsuleMixerEffect.OFF)
            }
            if (raw.length > 64) return CapsuleMixerProfile()
            val mode = CapsuleMixerEffect.entries.firstOrNull { it.id == raw.substringBefore(':') }
                ?: return CapsuleMixerProfile()
            val saved = raw.substringAfter(':', "").split(',')
            val levels = CapsuleMixerEffect.entries.drop(1).mapIndexed { i, effect ->
                saved.getOrNull(i)?.toIntOrNull()?.coerceIn(0, 100) ?: effect.defaultAmount
            }
            return CapsuleMixerProfile(mode, levels)
        }
    }
}

/** Keep effect history through strength changes and fade to dry before changing effects. */
internal class CapsuleMixerStage(private val rate: Int, private val channels: Int) {
    private var target = CapsuleMixerSetting()
    private var mode = CapsuleMixerEffect.OFF
    private var effect: MixerEffect? = null
    private var amount = 0.0
    private var transition = 0.0
    private val ramp = 1.0 / (rate * 0.035)
    private val tapeRamp = 1.0 / (rate * 0.095)
    var ceilingFraction = 0.0
        private set

    val active: Boolean get() = effect != null || target.enabled || ceilingFraction > 0.0

    fun set(setting: CapsuleMixerSetting) {
        target = if (setting.enabled) setting else CapsuleMixerSetting()
    }

    fun process(frame: DoubleArray, peak: Double) {
        val selected = mode == target.effect && target.enabled
        val goal = if (selected) target.amount.coerceIn(0, 100) * 0.01 else 0.0
        val effectRamp = if (mode == CapsuleMixerEffect.TAPE) tapeRamp else ramp
        amount += (goal - amount).coerceIn(-effectRamp, effectRamp)
        val transitionGoal = if (selected) 1.0 else 0.0
        transition += (transitionGoal - transition).coerceIn(-effectRamp, effectRamp)
        if (transition <= 1e-9 && mode != target.effect) {
            mode = target.effect
            effect = when (mode) {
                CapsuleMixerEffect.OFF -> null
                CapsuleMixerEffect.TAPE -> CapsuleTapeTransport(rate, channels)
                CapsuleMixerEffect.SOFT -> SoftMixerEffect(rate, channels)
                CapsuleMixerEffect.SUN -> SunMixerEffect(rate, channels)
                CapsuleMixerEffect.VINYL -> VinylMixerEffect(rate)
            }
        }
        effect?.apply(frame, peak, amount, transition)
        val ceilingGoal = if (effect != null) 1.0 else 0.0
        ceilingFraction += (ceilingGoal - ceilingFraction).coerceIn(-ramp, ramp)
    }
}

internal fun interface MixerEffect {
    fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double)
}

/** A level-dependent high-band dampener with a shared stereo envelope. */
private class SoftMixerEffect(rate: Int, channels: Int) : MixerEffect {
    private val filter = DoubleArray(channels)
    private var envelope = 0.0
    private val attack = mixerEnvelopeStep(0.0018, rate)
    private val release = mixerEnvelopeStep(0.070, rate)
    private val lowPass = mixerFilterStep(2_550.0, rate)

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        envelope += (peak - envelope) * if (peak > envelope) attack else release
        val transients = ((envelope - 0.055) / 0.30).coerceIn(0.0, 1.0)
        val damping = amount * (0.16 + 0.65 * transients)
        for (channel in frame.indices) {
            val sample = frame[channel]
            filter[channel] += (sample - filter[channel]) * lowPass
            frame[channel] = sample + (filter[channel] - sample) * damping
        }
    }
}

/** Add harmonics from the presence band within a fixed wet-signal budget. */
private class SunMixerEffect(rate: Int, channels: Int) : MixerEffect {
    private val upper = DoubleArray(channels)
    private val lower = DoubleArray(channels)
    private val harmonicLow = DoubleArray(channels)
    private val harmonicHigh = DoubleArray(channels)
    private val upperStep = mixerFilterStep(2_900.0, rate)
    private val lowerStep = mixerFilterStep(720.0, rate)
    private val highPass = mixerFilterStep(2_400.0, rate)
    private val lowPass = mixerFilterStep(7_200.0, rate)

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        for (channel in frame.indices) {
            val sample = frame[channel]
            upper[channel] += (sample - upper[channel]) * upperStep
            lower[channel] += (sample - lower[channel]) * lowerStep
            val band = (4.0 * (upper[channel] - lower[channel])).coerceIn(-1.0, 1.0)
            val shaped = band * abs(band)
            harmonicLow[channel] += (shaped - harmonicLow[channel]) * highPass
            val high = shaped - harmonicLow[channel]
            harmonicHigh[channel] += (high - harmonicHigh[channel]) * lowPass
            val wet = (0.36 * harmonicHigh[channel]).coerceIn(-0.14, 0.14)
            frame[channel] = sample * (1.0 - 0.14 * amount) + wet * amount
        }
    }
}

/** Short, rounded scratches tied to record rotation and the music envelope. */
private class VinylMixerEffect(rate: Int) : MixerEffect {
    private val rotation = (rate * 1.8).toInt()
    private val start = rate / 3
    private val duration = (rate * 0.055).toInt()
    private val pulseSeconds = duration.toDouble() / rate
    private val noisyHighStep = mixerFilterStep(4_200.0, rate)
    private val noisyLowStep = mixerFilterStep(950.0, rate)
    private val release = mixerEnvelopeStep(0.030, rate)
    private var turn = 0
    private var seed = 0x72bd3c6d
    private var high = 0.0
    private var low = 0.0
    private var envelope = 0.0

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        envelope = maxOf(peak, envelope + (peak - envelope) * release)
        val position = turn - start
        if (++turn == rotation) turn = 0
        if (position !in 0 until duration || envelope < 0.0001) return
        val phase = position.toDouble() / duration
        val skew = 4.0 * phase * (1.0 - phase)
        val pulse = skew * skew * amount
        seed = seed * 1_664_525 + 1_013_904_223
        val noise = (seed shr 8) / 8_388_608.0
        high += (noise - high) * noisyHighStep
        low += (noise - low) * noisyLowStep
        val scrape = 0.16 * sin(2 * PI * pulseSeconds * phase * (1_020.0 - 300.0 * phase))
        val scratch = (high - low + scrape) * pulse * (0.22 * envelope.coerceAtMost(0.5))
        for (channel in frame.indices) {
            frame[channel] = frame[channel] * (1.0 - 0.18 * pulse) + scratch
        }
    }
}

private fun mixerFilterStep(hz: Double, rate: Int): Double =
    1.0 - exp(-2.0 * PI * minOf(hz, rate * 0.28) / rate)

private fun mixerEnvelopeStep(seconds: Double, rate: Int): Double = 1.0 - exp(-1.0 / (rate * seconds))
