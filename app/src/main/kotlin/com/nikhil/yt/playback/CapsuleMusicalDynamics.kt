/* Capsule MUSIC — linked dynamic voicing. GPL-3.0. */
package com.nikhil.yt.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

internal enum class CapsuleVoicing { SUN, WARM, CLUB, FAST, SLOW }

/** Parallel complementary bands, with linked envelopes and smoothed gain changes.
 * No oscillators, makeup gain, stereo expansion or feedback. The output peak guard
 * remains mandatory: band differences are not an instantaneous peak bound. */
internal class CapsuleMusicalDynamics(private val rate: Int, channels: Int) {
    private val cuts = doubleArrayOf(85.0, 320.0, 1250.0, 4200.0)
    private val steps = DoubleArray(cuts.size) { 1 - exp(-2 * PI * minOf(cuts[it], rate * 0.30) / rate) }
    private val state = DoubleArray(channels * cuts.size)
    private val bands = DoubleArray(channels * 5)
    private val peaks = DoubleArray(5)
    private val fast = DoubleArray(5)
    private val slow = DoubleArray(5)
    private val gains = DoubleArray(5) { 1.0 }
    private val attack = step(0.008)
    private val release = step(0.075)
    private val average = step(0.24)
    private val gainAttack = step(0.025)
    private val gainRelease = step(0.10)
    private val updateEvery = (rate / 250).coerceAtLeast(1)
    private var countdown = 0
    private val desired = DoubleArray(5) { 1.0 }
    private var intensity = 0.0
    private var current = CapsuleVoicing.SUN
    private val fade = 1.0 / (rate * 0.055)
    val active: Boolean get() = intensity > 0.0

    fun apply(frame: DoubleArray, mode: CapsuleVoicing?, amount: Double, character: Double = 0.5, smoothAmount: Boolean = true) {
        val target = if (mode == current) amount.coerceIn(0.0, 1.0) else 0.0
        intensity = if (smoothAmount) intensity + (target - intensity).coerceIn(-fade, fade) else target
        if (intensity == 0.0 && mode != null && mode != current) {
            current = mode
            countdown = 0
        }
        peaks.fill(0.0)
        for (ch in frame.indices) {
            val x = frame[ch]
            var prev = 0.0
            for (i in cuts.indices) {
                val slot = ch * cuts.size + i
                state[slot] += (x - state[slot]) * steps[i]
                val b = state[slot] - prev
                prev = state[slot]
                bands[ch * 5 + i] = b
                peaks[i] = maxOf(peaks[i], abs(b))
            }
            val b = x - prev
            bands[ch * 5 + 4] = b
            peaks[4] = maxOf(peaks[4], abs(b))
        }
        for (i in 0..4) {
            fast[i] += (peaks[i] - fast[i]) * if (peaks[i] > fast[i]) attack else release
            slow[i] += (peaks[i] - slow[i]) * average
        }
        if (countdown-- <= 0) {
            countdown = updateEvery - 1
            val kick = ((fast[0] + fast[1] - slow[0] - slow[1]) / maxOf(0.035, slow[0] + slow[1])).coerceIn(0.0, 1.0)
            val bite = ((fast[4] - 0.025) / 0.20).coerceIn(0.0, 1.0)
            val mud = ((slow[1] - 0.55 * slow[2]) / maxOf(0.07, slow[1])).coerceIn(0.0, 1.0)
            val busy = character.coerceIn(0.0, 1.0)
            when (current) {
                CapsuleVoicing.SUN -> set(0.94, 0.84 - 0.19 * mud, 0.83, 1.0 - 0.10 * bite, 0.98 - 0.30 * bite)
                CapsuleVoicing.WARM -> set(0.83, 0.99 - 0.12 * mud, 1.0, 0.77 - 0.07 * bite, 0.58 - 0.18 * bite)
                CapsuleVoicing.CLUB -> set(0.91, 0.98 - 0.12 * mud, 0.72 - 0.19 * kick, 0.86 + 0.10 * kick, 0.80 - 0.14 * bite)
                CapsuleVoicing.FAST -> set(0.91, 0.73 + 0.13 * kick, 0.89, 1.0 - 0.06 * busy, 0.90 - 0.27 * bite)
                CapsuleVoicing.SLOW -> set(0.87, 0.97 - 0.15 * mud, 1.0, 0.84 - 0.05 * busy, 0.70 - 0.14 * bite)
            }
        }
        for (i in 0..4) gains[i] += (desired[i] - gains[i]) * if (desired[i] < gains[i]) gainAttack else gainRelease
        if (intensity <= 0.0) return
        for (ch in frame.indices) {
            var cut = 0.0
            for (i in 0..4) cut += (1.0 - gains[i]) * bands[ch * 5 + i]
            frame[ch] -= intensity * cut
        }
    }

    private fun set(a: Double, b: Double, c: Double, d: Double, e: Double) {
        desired[0] = a; desired[1] = b; desired[2] = c; desired[3] = d; desired[4] = e
    }
    private fun step(seconds: Double) = 1.0 - exp(-1.0 / (rate * seconds))
}

/** Version coloration is independent from the user's five-band EQ and mixer. */
internal class CapsuleVersionStage(private val rate: Int, private val channels: Int) {
    private var dynamics: CapsuleMusicalDynamics? = null
    private var room: FloatArray? = null
    private val roomFrames = (rate * 0.27).toInt().coerceAtLeast(2)
    private val taps = doubleArrayOf(0.023, 0.037, 0.053, 0.071, 0.097, 0.131, 0.173, 0.233)
        .map { (rate * it).toInt().coerceIn(1, roomFrames - 1) }.toIntArray()
    private val weights = doubleArrayOf(0.21, 0.18, 0.16, 0.13, 0.11, 0.09, 0.07, 0.05)
    private val positions = IntArray(taps.size)
    private var smoothedCharacter = 0.76
    private val characterStep = 1.0 - exp(-1.0 / (rate * 0.65))
    private val tone = DoubleArray(channels)
    private val toneStep = 1.0 - exp(-2 * PI * minOf(2400.0, rate * 0.28) / rate)
    private var cursor = 0
    private var wet = 0.0
    private val ramp = 1.0 / (rate * 0.16)
    val active: Boolean get() = dynamics?.active == true || wet > 0.0

    fun apply(frame: DoubleArray, mode: CapsuleVoicing?, character: Double) {
        if (mode != null && dynamics == null) dynamics = CapsuleMusicalDynamics(rate, channels)
        smoothedCharacter += (character.coerceIn(0.0, 1.0) - smoothedCharacter) * characterStep
        dynamics?.apply(frame, mode, if (mode == null) 0.0 else 0.95, smoothedCharacter)
        if (mode == null && dynamics?.active == false) dynamics = null
        val targetWet = if (mode == CapsuleVoicing.SLOW) 0.22 - 0.09 * smoothedCharacter else 0.0
        wet += (targetWet - wet).coerceIn(-ramp, ramp)
        if (wet <= 0.0) {
            room = null
            cursor = 0
            tone.fill(0.0)
            return
        }
        val history = room ?: FloatArray(roomFrames * channels).also { room = it }
        for (i in taps.indices) {
            val offset = cursor - taps[i]
            positions[i] = (if (offset < 0) offset + roomFrames else offset) * channels
        }
        for (ch in frame.indices) {
            val x = frame[ch]
            // Finite, damped multi-tap ambience. No recursive feedback or channel mixing.
            var reflection = 0.0
            for (i in positions.indices) reflection += weights[i] * history[positions[i] + ch]
            tone[ch] += (reflection - tone[ch]) * toneStep
            history[cursor * channels + ch] = x.toFloat()
            frame[ch] = x + wet * (tone[ch] - x)
        }
        if (++cursor == roomFrames) cursor = 0
    }
}
