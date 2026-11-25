/* Capsule MUSIC — signal-following record and DJ character. GPL-3.0. */
package com.nikhil.yt.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

private fun characterStep(hz: Double, rate: Int) =
    1.0 - exp(-2.0 * PI * minOf(hz, rate * 0.28) / rate)
private fun characterTime(seconds: Double, rate: Int) = 1.0 - exp(-1.0 / (seconds * rate))

/** Quiet, band-limited surface texture. No samples, DC, rumble or full-scale impulses. */
internal class CapsuleRecordTexture(private val rate: Int, channels: Int) {
    private val envelope = DoubleArray(channels)
    private val attack = characterTime(0.012, rate)
    private val release = characterTime(0.090, rate)
    private val lowerStep = characterStep(650.0, rate)
    private val upperStep = characterStep(2800.0, rate)
    private val tickDecay = exp(-1.0 / (rate * 0.006))
    private var state = 0x51a7b5c3
    private var lower = 0.0
    private var upper = 0.0
    private var tick = 0.0
    private var countdown = 0
    private var started = false
    private var elapsed = 0
    private var quiet = 0
    private var intro = 0.0

    private fun random(): Double {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        return (state ushr 1).toDouble() / 1073741824.0 - 1.0
    }

    fun apply(frame: DoubleArray, amount: Double) {
        var peak = 0.0
        for (ch in frame.indices) {
            val level = abs(frame[ch])
            envelope[ch] += (level - envelope[ch]) * if (level > envelope[ch]) attack else release
            peak = maxOf(peak, envelope[ch])
        }
        if (peak < 0.0015) {
            if (quiet < rate) quiet++
            if (quiet >= rate / 2) { started = false; elapsed = 0; countdown = 0; tick = 0.0 }
        } else quiet = 0
        if (!started && peak > 0.012) { started = true; elapsed = 0; countdown = 0; intro = 1.0 }
        if (!started) return
        if (elapsed < rate) elapsed++
        intro *= expIntro
        if (countdown-- <= 0) {
            val polarity = if (random() < 0.0) -1.0 else 1.0
            tick = polarity * (0.45 + 0.55 * abs(random()))
            val interval = if (elapsed < rate / 3) 0.048 else 0.22
            countdown = (rate * interval * (1.0 + 0.65 * random())).toInt().coerceAtLeast(1)
        }
        val white = random()
        // A decaying signed groove transient produces a recognizable tick rather
        // than burying the event in nearly inaudible modulated white noise.
        val excitation = (white * (0.30 + 0.35 * abs(tick)) + 0.65 * tick).coerceIn(-1.0, 1.0)
        tick *= tickDecay
        lower += (excitation - lower) * lowerStep
        upper += (excitation - upper) * upperStep
        val texture = (upper - lower).coerceIn(-1.0, 1.0)
        for (ch in frame.indices) {
            val level = envelope[ch]
            val gate = ((level - 0.002) / 0.018).coerceIn(0.0, 1.0)
            // Follow each channel independently; a silent channel stays silent.
            val budget = minOf(0.024, level * (0.20 + 0.22 * intro))
            frame[ch] += texture * budget * gate * amount.coerceIn(0.0, 1.0)
        }
    }
    private val expIntro = exp(-1.0 / (rate * 0.19))
}

/** Beat-following club sweeps on the current frame only.
 * No history buffer, sample replacement, time displacement or broadband muting. */
internal class CapsuleAutoDjGesture(private val rate: Int, channels: Int) {
    private val bass = DoubleArray(channels)
    private val body = DoubleArray(channels)
    private val air = DoubleArray(channels)
    private val bassStep = characterStep(150.0, rate)
    private val bodyStep = characterStep(1250.0, rate)
    private val airStep = characterStep(4200.0, rate)
    private val fastStep = characterTime(0.009, rate)
    private val slowStep = characterTime(0.22, rate)
    private var fast = 0.0
    private var slow = 0.0
    private var sinceBeat = rate * 2
    private var sinceEvent = rate * 5
    private var period = 0.0
    private var stable = 0
    private var beats = 0
    private var armed = true
    private var gesture = -1
    private var gestureLength = 1
    internal var eventsStarted = 0
        private set

    fun apply(frame: DoubleArray, amount: Double) {
        val strength = amount.coerceIn(0.0, 1.0)
        var onsetLevel = 0.0
        var fullPeak = 0.0
        for (ch in frame.indices) {
            val x = frame[ch]
            bass[ch] += (x - bass[ch]) * bassStep
            body[ch] += (x - body[ch]) * bodyStep
            air[ch] += (x - air[ch]) * airStep
            onsetLevel = maxOf(onsetLevel, abs(bass[ch]) + 0.28 * abs(body[ch] - bass[ch]))
            fullPeak = maxOf(fullPeak, abs(x))
        }
        fast += (onsetLevel - fast) * fastStep
        slow += (onsetLevel - slow) * slowStep
        if (sinceBeat < rate * 3) sinceBeat++
        if (sinceEvent < rate * 6) sinceEvent++
        if (fast < maxOf(0.014, slow * 1.13)) armed = true
        if (armed && fast > maxOf(0.025, slow * 1.42) && sinceBeat >= rate / 5) {
            armed = false
            val interval = sinceBeat.toDouble()
            if (interval in rate * 0.25..rate * 1.05) {
                if (period > 0.0 && abs(interval - period) < period * 0.24) {
                    stable = minOf(stable + 1, 32)
                    beats = if (beats == 32) 5 else beats + 1
                    period += (interval - period) * 0.2
                } else { period = interval; stable = 0; beats = 0 }
            } else { period = 0.0; stable = 0; beats = 0 }
            sinceBeat = 0
            if (stable >= 2 && (beats == 2 || beats % 4 == 0) && sinceEvent >= rate * 4 &&
                gesture < 0 && strength >= 0.10 && fullPeak > 0.025) {
                eventsStarted++
                sinceEvent = 0
                gesture = 0
                gestureLength = (period * 3.0).toInt()
                    .coerceIn((rate * 0.9).toInt(), (rate * 2.8).toInt())
            }
        }
        if (gesture < 0) return
        val p = gesture.toDouble() / gestureLength
        // Slow tonal buildup, then release over the next beat. Smoothstep
        // gives zero slope at both ends and at the turning point.
        val t = if (p < 0.66) p / 0.66 else (1.0 - p) / 0.34
        val sweep = t * t * (3.0 - 2.0 * t) * strength
        for (ch in frame.indices) {
            val x = frame[ch]
            val presence = air[ch] - body[ch]
            val top = x - air[ch]
            val mids = body[ch] - bass[ch]
            // Preserve the direct bass and most of the vocal band.
            frame[ch] = x - sweep * (0.12 * mids + 0.38 * presence + 0.56 * top)
        }
        if (++gesture >= gestureLength) gesture = -1
    }
}
