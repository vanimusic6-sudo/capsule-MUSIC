/* Capsule MUSIC — independent PCM effects. GPL-3.0. */
package com.nikhil.yt.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
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
        // Arm the output ceiling before starting the audible effect ramp.
        if (target.enabled) ceilingFraction = 1.0
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
                CapsuleMixerEffect.VINYL -> VinylMixerEffect(rate, channels)
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
    private var body = 0.0
    private val attack = mixerEnvelopeStep(0.0025, rate)
    private val release = mixerEnvelopeStep(0.045, rate)
    private val bodyStep = mixerEnvelopeStep(0.16, rate)
    private val lowPass = mixerFilterStep(2_700.0, rate)

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        envelope += (peak - envelope) * if (peak > envelope) attack else release
        body += (envelope - body) * bodyStep
        val accent = ((envelope - body - 0.008) / 0.16).coerceIn(0.0, 1.0)
        val brightness = ((envelope - 0.14) / 0.26).coerceIn(0.0, 1.0)
        val damping = amount * (0.25 + 0.35 * accent + 0.27 * brightness)
        for (channel in frame.indices) {
            val sample = frame[channel]
            filter[channel] += (sample - filter[channel]) * lowPass
            frame[channel] = sample + (filter[channel] - sample) * damping
        }
    }
}

/** Presence and harmonics from upper mids, with a shared allowance on loud passages. */
private class SunMixerEffect(rate: Int, channels: Int) : MixerEffect {
    private val upper = DoubleArray(channels)
    private val lower = DoubleArray(channels)
    private val harmonicLow = DoubleArray(channels)
    private val harmonicHigh = DoubleArray(channels)
    private val airBase = DoubleArray(channels)
    private val upperStep = mixerFilterStep(3_800.0, rate)
    private val lowerStep = mixerFilterStep(1_350.0, rate)
    private val airStep = mixerFilterStep(4_200.0, rate)
    private val highPass = mixerFilterStep(2_350.0, rate)
    private val lowPass = mixerFilterStep(5_800.0, rate)
    private val attack = mixerEnvelopeStep(0.008, rate)
    private val release = mixerEnvelopeStep(0.085, rate)
    private var envelope = 0.0

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        envelope += (peak - envelope) * if (peak > envelope) attack else release
        val room = 1.0 - 0.53 * ((envelope - 0.18) / 0.48).coerceIn(0.0, 1.0)
        for (channel in frame.indices) {
            val sample = frame[channel]
            upper[channel] += (sample - upper[channel]) * upperStep
            lower[channel] += (sample - lower[channel]) * lowerStep
            airBase[channel] += (sample - airBase[channel]) * airStep
            val presence = upper[channel] - lower[channel]
            val band = 5.8 * presence
            val driven = band / (1.0 + 1.1 * abs(band))
            val shaped = driven * (0.62 * driven + 0.16 * abs(driven))
            harmonicLow[channel] += (shaped - harmonicLow[channel]) * highPass
            val high = shaped - harmonicLow[channel]
            harmonicHigh[channel] += (high - harmonicHigh[channel]) * lowPass
            val air = sample - airBase[channel]
            val wet = (0.54 * presence + 0.34 * air + 0.42 * harmonicHigh[channel]).coerceIn(-0.19, 0.19)
            frame[channel] = sample * (1.0 - 0.10 * amount) + wet * amount * room
        }
    }
}

/** Pull and release a short slice of the actual record, using a band-limited delay tap. */
private class VinylMixerEffect(rate: Int, private val channels: Int) : MixerEffect {
    private val captureStride = (rate + 23_999) / 24_000
    private val captureRate = rate.toDouble() / captureStride
    private val capacity = Integer.highestOneBit((captureRate * 0.21).toInt()) shl 1
    private val ring = FloatArray(capacity * channels)
    private val bass = DoubleArray(channels)
    private val mid = DoubleArray(channels)
    private val needle = DoubleArray(channels)
    private val lower = mixerFilterStep(145.0, rate)
    private val upper = mixerFilterStep(minOf(3_250.0, captureRate * 0.116), rate)
    private val release = mixerEnvelopeStep(0.032, rate)
    private val start = rate / 2
    private val duration = (rate * 0.221).toInt()
    private val interval = (rate * 5.8).toInt()
    private var rotation = 0
    private var cursor = capacity - 1
    private var capturePhase = 0
    private var stored = 0
    private var envelope = 0.0

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        envelope = maxOf(peak, envelope + (peak - envelope) * release)
        val capture = capturePhase == 0
        if (capture) {
            cursor = (cursor + 1) and (capacity - 1)
            stored = minOf(stored + 1, capacity)
        }
        for (channel in frame.indices) {
            val sample = frame[channel]
            bass[channel] += (sample - bass[channel]) * lower
            mid[channel] += (sample - bass[channel] - mid[channel]) * upper
            needle[channel] += (mid[channel] - needle[channel]) * upper
            if (capture) ring[cursor * channels + channel] = needle[channel].toFloat()
        }
        val position = rotation - start
        if (position in 0 until duration && stored > (captureRate * 0.17).toInt() && envelope > 0.003) {
            val phase = position.toDouble() / duration
            val window = (4.0 * phase * (1.0 - phase)).let { it * it }
            val delay = 0.102 + 0.061 * sin(2.0 * PI * (phase - 0.43))
            val tap = cursor + capturePhase.toDouble() / captureStride - delay * captureRate
            val first = floor(tap).toInt()
            val fraction = tap - first
            val left = (first and (capacity - 1)) * channels
            val right = ((first + 1) and (capacity - 1)) * channels
            val mix = 0.90 * amount * window * ((envelope - 0.003) / 0.028).coerceIn(0.0, 1.0)
            for (channel in frame.indices) {
                val wet = ring[left + channel] + (ring[right + channel] - ring[left + channel]) * fraction
                frame[channel] += (wet * 0.96 - frame[channel]) * mix
            }
        }
        if (++capturePhase == captureStride) capturePhase = 0
        if (++rotation == interval) rotation = 0
    }
}

private fun mixerFilterStep(hz: Double, rate: Int): Double =
    1.0 - exp(-2.0 * PI * minOf(hz, rate * 0.28) / rate)

private fun mixerEnvelopeStep(seconds: Double, rate: Int): Double = 1.0 - exp(-1.0 / (rate * seconds))
