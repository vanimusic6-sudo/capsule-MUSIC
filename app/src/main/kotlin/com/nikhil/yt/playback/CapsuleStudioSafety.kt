/* Capsule MUSIC — signal-relative studio load guard and record coloration. GPL-3.0. */
package com.nikhil.yt.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Estimates sustained relative digital energy; not a transducer or SPL model. */
internal class CapsuleStudioLoadGuard(rate: Int, channels: Int) {
    private val dryLow = DoubleArray(channels)
    private val wetLow = DoubleArray(channels)
    private val dryHighBase = DoubleArray(channels)
    private val wetHighBase = DoubleArray(channels)
    private val lowStep = coefficient(85.0, rate)
    private val highStep = coefficient(4500.0, rate)
    private val powerStep = 1.0 - exp(-1.0 / (rate * 0.18))
    private val attack = 1.0 - exp(-1.0 / (rate * 0.055))
    private val release = 1.0 - exp(-1.0 / (rate * 0.45))
    private var dryBass = 0.0
    private var wetBass = 0.0
    private var dryTreble = 0.0
    private var wetTreble = 0.0
    private var gain = 1.0
    private val updateEvery = (rate / 250).coerceAtLeast(1)
    private var countdown = 0
    private var target = 1.0

    fun observe(dry: DoubleArray, wet: DoubleArray, protection: Double): Double {
        var dl = 0.0; var wl = 0.0; var dh = 0.0; var wh = 0.0
        for (ch in dry.indices) {
            val x = dry[ch]
            val y = wet[ch]
            dryLow[ch] += (x - dryLow[ch]) * lowStep
            wetLow[ch] += (y - wetLow[ch]) * lowStep
            val d = x - dryHighBase[ch]; val w = y - wetHighBase[ch]
            dryHighBase[ch] += d * highStep
            wetHighBase[ch] += w * highStep
            dl = maxOf(dl, dryLow[ch] * dryLow[ch])
            wl = maxOf(wl, wetLow[ch] * wetLow[ch])
            dh = maxOf(dh, d * d); wh = maxOf(wh, w * w)
        }
        dryBass += (dl - dryBass) * powerStep
        wetBass += (wl - wetBass) * powerStep
        dryTreble += (dh - dryTreble) * powerStep
        wetTreble += (wh - wetTreble) * powerStep
        if (countdown-- <= 0) {
            countdown = updateEvery - 1
            // Allow modest musical redistribution, not sustained added extreme-band power.
            val bass = sqrt((1.26 * dryBass + 0.0009) / maxOf(0.0009, wetBass)).coerceAtMost(1.0)
            val treble = sqrt((1.26 * dryTreble + 0.0009) / maxOf(0.0009, wetTreble)).coerceAtMost(1.0)
            target = minOf(bass, treble)
        }
        val goal = 1.0 - protection.coerceIn(0.0, 1.0) * (1.0 - target)
        gain += (goal - gain) * if (goal < gain) attack else release
        return gain.coerceIn(0.0, 1.0)
    }
}

/** Warm record playback: gentle band-limited groove compression, no random clicks,
 * noise generator, positive makeup gain or artificial subsonic rumble. */
internal class CapsuleRecordVoicing(rate: Int, channels: Int) {
    private val sub = DoubleArray(channels)
    private val low = DoubleArray(channels)
    private val mid = DoubleArray(channels)
    private val top = DoubleArray(channels)
    private val subStep = coefficient(48.0, rate)
    private val lowStep = coefficient(280.0, rate)
    private val midStep = coefficient(2400.0, rate)
    private val topStep = coefficient(5700.0, rate)
    private val attack = 1.0 - exp(-1.0 / (rate * 0.012))
    private val release = 1.0 - exp(-1.0 / (rate * 0.12))
    private var envelope = 0.0

    fun apply(frame: DoubleArray, amount: Double) {
        var peak = 0.0
        for (x in frame) peak = maxOf(peak, abs(x))
        envelope += (peak - envelope) * if (peak > envelope) attack else release
        val pressure = ((envelope - 0.10) / 0.45).coerceIn(0.0, 1.0)
        val mix = amount.coerceIn(0.0, 1.0)
        for (ch in frame.indices) {
            val x = frame[ch]
            sub[ch] += (x - sub[ch]) * subStep
            low[ch] += (x - low[ch]) * lowStep
            mid[ch] += (x - mid[ch]) * midStep
            top[ch] += (x - top[ch]) * topStep
            val body = mid[ch] - low[ch]
            val drive = body * body
            val groove = (0.10 + 0.14 * pressure) * drive / (0.035 + drive)
            val color = x - 0.22 * sub[ch] - (0.28 + 0.12 * pressure) * (x - top[ch]) - groove * body
            frame[ch] = x + mix * (color - x)
        }
    }
}

private fun coefficient(hz: Double, rate: Int): Double =
    1.0 - exp(-2.0 * PI * minOf(hz, rate * 0.28) / rate)
