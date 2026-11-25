/* Capsule MUSIC — independent PCM effects. GPL-3.0. */
package com.nikhil.yt.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

internal enum class CapsuleMixerEffect(val id: String, val defaultAmount: Int) {
    OFF("off", 0),
    TAPE("tape", 72),
    SOFT("soft", 82),
    SUN("sun", 75),
    VINYL("vinyl", 78),
}

internal data class CapsuleDjControls(
    val jog: Float = 0f,
    val brake: Boolean = false,
    val filter: Float = 0f,
) {
    fun bounded(): CapsuleDjControls {
        if (jog in -1f..1f && filter in -1f..1f) return this
        return copy(
            jog = if (jog.isFinite()) jog.coerceIn(-1f, 1f) else 0f,
            filter = if (filter.isFinite()) filter.coerceIn(-1f, 1f) else 0f,
        )
    }
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
    var djControls = CapsuleDjControls()
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
                CapsuleMixerEffect.VINYL -> CapsuleDjDeck(rate, channels) { djControls }
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

/** Reveal lower-mid body by subtracting sub-bass and treble rather than adding gain. */
private class SoftMixerEffect(rate: Int, channels: Int) : MixerEffect {
    private val bass = DoubleArray(channels)
    private val body = DoubleArray(channels)
    private val trebleBase = DoubleArray(channels)
    private val bassStep = mixerFilterStep(150.0, rate)
    private val bodyStep = mixerFilterStep(930.0, rate)
    private val highStep = mixerFilterStep(3_100.0, rate)
    private val attack = mixerEnvelopeStep(0.002, rate)
    private val release = mixerEnvelopeStep(0.065, rate)
    private var sheen = 0.0

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        var treblePeak = 0.0
        for (channel in frame.indices) {
            val x = frame[channel]
            bass[channel] += (x - bass[channel]) * bassStep
            body[channel] += (x - body[channel]) * bodyStep
            trebleBase[channel] += (x - trebleBase[channel]) * highStep
            treblePeak = maxOf(treblePeak, abs(x - trebleBase[channel]))
        }
        sheen += (treblePeak - sheen) * if (treblePeak > sheen) attack else release
        val damping = 0.27 + 0.33 * ((sheen - 0.035) / 0.22).coerceIn(0.0, 1.0)
        for (channel in frame.indices) {
            val x = frame[channel]
            // The body stays dry; attenuating the adjacent regions creates warmth
            // without boosting long bass notes or compressing the entire recording.
            frame[channel] = x - amount * (
                0.24 * bass[channel] + 0.09 * (x - body[channel]) +
                    damping * (x - trebleBase[channel])
                )
        }
    }
}

/** Subtractive presence: no saturation, generated harmonics or treble makeup gain. */
private class SunMixerEffect(rate: Int, channels: Int) : MixerEffect {
    private val lower = DoubleArray(channels)
    private val upper = DoubleArray(channels)
    private val airBase = DoubleArray(channels)
    private val lowerStep = mixerFilterStep(320.0, rate)
    private val upperStep = mixerFilterStep(1_350.0, rate)
    private val airStep = mixerFilterStep(4_500.0, rate)
    private val attack = mixerEnvelopeStep(0.003, rate)
    private val release = mixerEnvelopeStep(0.10, rate)
    private var brightness = 0.0

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        var airPeak = 0.0
        for (channel in frame.indices) {
            val x = frame[channel]
            lower[channel] += (x - lower[channel]) * lowerStep
            upper[channel] += (x - upper[channel]) * upperStep
            airBase[channel] += (x - airBase[channel]) * airStep
            airPeak = maxOf(airPeak, abs(x - airBase[channel]))
        }
        brightness += (airPeak - brightness) * if (airPeak > brightness) attack else release
        val fatigue = ((brightness - 0.035) / 0.23).coerceIn(0.0, 1.0)
        val bodyCut = 0.31 * (1.0 - 0.50 * fatigue)
        val veilCut = 0.17 * (1.0 - 0.55 * fatigue)
        for (channel in frame.indices) {
            val x = frame[channel]
            frame[channel] = x - amount * (bodyCut * lower[channel] +
                veilCut * upper[channel] + 0.16 * fatigue * (x - airBase[channel]))
        }
    }
}

/** Automatic club voicing with optional momentary manual jog/brake over recent PCM. */
private class CapsuleDjDeck(
    private val rate: Int,
    private val channels: Int,
    private val readControls: () -> CapsuleDjControls,
) : MixerEffect {
    private val stride = (rate + 23_999) / 24_000
    private val captureRate = rate.toDouble() / stride
    private val capacity = Integer.highestOneBit((captureRate * 0.22).toInt()) shl 1
    private val ring = FloatArray(capacity * channels)
    private val bass = DoubleArray(channels)
    private val mid = DoubleArray(channels)
    private val needle = DoubleArray(channels)
    private val tone = DoubleArray(channels)
    private val lowStep = mixerFilterStep(135.0, rate)
    private val highStep = mixerFilterStep(minOf(3_200.0, captureRate * 0.115), rate)
    private val toneStep = mixerFilterStep(790.0, rate)
    private val glide = mixerEnvelopeStep(0.018, rate)
    private val brakeGlide = mixerEnvelopeStep(0.072, rate)
    private val blendStep = 1.0 / (0.022 * rate)
    private var frame = 0
    private var cursor = capacity - 1
    private var stored = 0
    private var activeGesture = false
    private var readAge = 0.041
    private var motionRate = 1.0
    private var mix = 0.0
    private var brakeWeight = 0.0
    private var tonePosition = 0.0
    private var reachedEdge = false
    private var fastEnvelope = 0.0
    private var slowEnvelope = 0.0
    private val punchAttack = mixerEnvelopeStep(0.006, rate)
    private val punchRelease = mixerEnvelopeStep(0.055, rate)
    private val grooveStep = mixerEnvelopeStep(0.18, rate)

    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        val control = readControls()
        val touched = control.brake || abs(control.jog) > 0.015f
        if (touched && !activeGesture && mix <= 0.0) {
            readAge = 0.041
            motionRate = 1.0
            reachedEdge = false
        }
        activeGesture = touched
        val target = when {
            control.brake -> 0.025
            touched -> control.jog.toDouble() * 1.52
            else -> 1.0
        }
        motionRate += (target - motionRate) * if (control.brake) brakeGlide else glide
        brakeWeight += ((if (control.brake) 1.0 else 0.0) - brakeWeight) * glide
        tonePosition += (control.filter - tonePosition) * glide
        val capture = this.frame == 0
        if (capture) {
            cursor = (cursor + 1) and (capacity - 1)
            stored = minOf(stored + 1, capacity)
        }
        for (channel in frame.indices) {
            val sample = frame[channel]
            bass[channel] += (sample - bass[channel]) * lowStep
            mid[channel] += (sample - bass[channel] - mid[channel]) * highStep
            needle[channel] += (mid[channel] - needle[channel]) * highStep
            if (capture) ring[cursor * channels + channel] = needle[channel].toFloat()
        }
        val hasTrack = stored > (captureRate * 0.17).toInt()
        if (!control.brake && touched && reachedEdge &&
            (readAge > 0.16 && motionRate > 1.02 || readAge < 0.009 && motionRate < 0.98)
        ) reachedEdge = false
        val targetMix = if (touched && hasTrack && (!reachedEdge || control.brake)) 1.0 else 0.0
        mix += (targetMix - mix).coerceIn(-blendStep, blendStep)
        if (mix > 0.0) {
            val tap = cursor + this.frame.toDouble() / stride - readAge * captureRate
            val first = floor(tap).toInt()
            val fraction = tap - first
            val left = (first and (capacity - 1)) * channels
            val right = ((first + 1) and (capacity - 1)) * channels
            val wetScale = 0.91 + (motionRate.coerceIn(0.0, 1.0) - 0.91) * brakeWeight
            for (channel in frame.indices) {
                val scrub = ring[left + channel] + (ring[right + channel] - ring[left + channel]) * fraction
                frame[channel] += (scrub * wetScale - frame[channel]) * mix * (0.66 + 0.34 * amount)
            }
            val nextAge = readAge + (1.0 - motionRate) / rate
            readAge = nextAge.coerceIn(0.007, 0.163)
            if (nextAge != readAge) reachedEdge = true
            if (reachedEdge && nextAge == readAge && touched && !control.brake) reachedEdge = false
        }
        // Automatic mode changes the mix even without touches. Keep the kick
        // and transient intact, make space in the middle, and tame busy highs.
        // No synthetic beat, periodic scratching, stereo widening or makeup gain.
        fastEnvelope += (peak - fastEnvelope) * if (peak > fastEnvelope) punchAttack else punchRelease
        slowEnvelope += (peak - slowEnvelope) * grooveStep
        val punch = ((fastEnvelope - slowEnvelope) / maxOf(0.06, slowEnvelope)).coerceIn(0.0, 1.0)
        val automatic = amount * (1.0 - mix) * (1.0 - abs(tonePosition).coerceAtMost(1.0))
        for (channel in frame.indices) {
            val dry = frame[channel]
            val high = dry - bass[channel] - mid[channel]
            frame[channel] = dry - automatic * (
                0.08 * bass[channel] + (0.36 - 0.11 * punch) * mid[channel] +
                    (0.15 - 0.09 * punch) * high + (0.055 - 0.045 * punch) * dry
                )
        }
        if (abs(tonePosition) > 0.00001) {
            val strength = abs(tonePosition) * (0.52 + 0.48 * amount)
            for (channel in frame.indices) {
                val dry = frame[channel]
                tone[channel] += (dry - tone[channel]) * toneStep
                val filtered = if (tonePosition < 0.0) tone[channel] else dry - tone[channel]
                val driven = filtered * (1.0 - 0.22 * strength * abs(filtered).coerceAtMost(1.0))
                frame[channel] = dry + strength * (driven - dry)
            }
        } else {
            for (channel in frame.indices) tone[channel] += (frame[channel] - tone[channel]) * toneStep
        }
        if (++this.frame == stride) this.frame = 0
    }
}

private fun mixerFilterStep(hz: Double, rate: Int): Double =
    1.0 - exp(-2.0 * PI * minOf(hz, rate * 0.28) / rate)

private fun mixerEnvelopeStep(seconds: Double, rate: Int): Double = 1.0 - exp(-1.0 / (rate * seconds))
