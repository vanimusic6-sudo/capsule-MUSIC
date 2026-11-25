/* Capsule MUSIC — independent PCM effects. GPL-3.0. */
package com.nikhil.yt.playback


internal enum class CapsuleMixerEffect(val id: String, val defaultAmount: Int) {
    OFF("off", 0),
    TAPE("tape", 72),
    SOFT("soft", 82),
    // Keep legacy IDs/ordinals: sun now selects record coloration; vinyl selects automatic DJ.
    SUN("sun", 75),
    VINYL("vinyl", 78),
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
                CapsuleMixerEffect.SUN -> RecordMixerEffect(rate, channels)
                CapsuleMixerEffect.VINYL -> ClubMixerEffect(rate, channels)
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

/** Dynamic warmth without makeup gain or added bass harmonics. */
private class SoftMixerEffect(rate: Int, channels: Int) : MixerEffect {
    private val dynamics = CapsuleMusicalDynamics(rate, channels)
    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) =
        dynamics.apply(frame, CapsuleVoicing.WARM, amount, smoothAmount = false)
}

/** Record coloration, independent of the tape transport. */
private class RecordMixerEffect(rate: Int, channels: Int) : MixerEffect {
    private val record = CapsuleRecordVoicing(rate, channels)
    private val texture = CapsuleRecordTexture(rate, channels)
    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        record.apply(frame, amount)
        texture.apply(frame, amount)
    }
}

/** Automatic club mix and short beat-gated gestures; no manual controls. */
private class ClubMixerEffect(rate: Int, channels: Int) : MixerEffect {
    private val dynamics = CapsuleMusicalDynamics(rate, channels)
    private val gesture = CapsuleAutoDjGesture(rate, channels)
    override fun apply(frame: DoubleArray, peak: Double, amount: Double, transition: Double) {
        gesture.apply(frame, amount)
        dynamics.apply(frame, CapsuleVoicing.CLUB, amount, smoothAmount = false)
    }
}
