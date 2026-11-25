/* Capsule MUSIC — bounded per-track musical analysis. GPL-3.0. */
package com.nikhil.yt.playback

import java.nio.ByteBuffer
import kotlin.math.*

internal data class CapsuleTempoShape(val speed: Float, val pitch: Float)

internal data class CapsuleTrackSound(
    val levelsMb: List<Int>, val pace: Float, val bpm: Float = 0f, val rhythmConfidence: Float = 0f,
) {
    fun tempo(variant: SoundStudioTempoVariant): CapsuleTempoShape {
        // A version is a stable varispeed master, not a live tempo estimate.
        // Exactly four semitones in either direction, preserving rhythmic relationships.
        val rate = when (variant) {
            SoundStudioTempoVariant.NORMAL -> 1f
            SoundStudioTempoVariant.SPEED_UP -> 1.2599211f
            SoundStudioTempoVariant.SLOWED -> 0.7937005f
        }
        return CapsuleTempoShape(rate, rate)
    }

    companion object { val NEUTRAL = CapsuleTrackSound(emptyList(), 0.76f) }
}

/** Independent broad bandpass energy, level-normalized excerpts and onset periodicity.
 * Once a plan is published, the real-time detector is released by its owner. */
internal class CapsuleStudioAnalysis(private val sampleRate: Int, private val channels: Int) {
    private val centers = doubleArrayOf(80.0, 400.0, 1000.0, 2800.0, 8500.0)
    private val bank = Array(centers.size) { i -> AnalysisBand(centers[i], sampleRate, channels) }
    private val power = DoubleArray(5)
    private val shares = DoubleArray(5)
    private val energy = DoubleArray(5)
    private val onset = DoubleArray(600)
    private val blockSize = (sampleRate / 50).coerceAtLeast(1)
    private var frames = 0
    private var blocks = 0
    private var audible = 0
    private var blockPower = 0.0
    private var blockPeak = 0.0
    private var envelope = 0.0
    private var previous = 0.0
    private var previousBass = 0.0
    private var attacks = 0
    private var lastAttack = -10
    private var activity = 0.0
    private var crest = 0.0

    fun observe(input: ByteBuffer): CapsuleTrackSound? {
        val frameBytes = channels * Short.SIZE_BYTES
        var index = input.position()
        while (index + frameBytes <= input.limit()) {
            for (ch in 0 until channels) {
                val x = input.getShort(index) / 32768.0
                index += Short.SIZE_BYTES
                blockPower += x * x
                blockPeak = maxOf(blockPeak, abs(x))
                for (i in bank.indices) {
                    val b = bank[i].process(x, ch)
                    power[i] += b * b
                }
            }
            if (++frames < blockSize) continue
            finishBlock()
            if (audible >= 210 || blocks >= onset.size && audible >= 60) return result()
            if (blocks >= onset.size) resetWindow()
        }
        return null
    }

    private fun finishBlock() {
        val rms = sqrt(blockPower / (blockSize * channels))
        val total = power.sum()
        val bass = sqrt((power[0] + power[1]) / (blockSize * channels))
        val flux = (rms - previous).coerceAtLeast(0.0) + 0.65 * (bass - previousBass).coerceAtLeast(0.0)
        onset[blocks] = (flux / maxOf(0.025, envelope)).coerceIn(0.0, 2.0)
        blocks++
        if (rms > 0.006 && total > 1e-12) {
            audible++
            for (i in power.indices) {
                val share = power[i] / total
                shares[i] += share
                energy[i] += share * share
            }
            if (rms > maxOf(0.018, envelope * 1.32) && rms - previous > maxOf(0.009, envelope * 0.16) && blocks - lastAttack >= 4) {
                attacks++
                lastAttack = blocks
            }
            activity += (abs(rms - previous) / maxOf(0.035, envelope)).coerceAtMost(1.0)
            crest += (blockPeak / maxOf(rms, 0.006)).coerceIn(1.0, 8.0)
        }
        envelope += 0.14 * (rms - envelope)
        previous = rms
        previousBass = bass
        frames = 0
        blockPower = 0.0
        blockPeak = 0.0
        power.fill(0.0)
    }

    private fun resetWindow() {
        blocks = 0; audible = 0; attacks = 0; lastAttack = -10; activity = 0.0; crest = 0.0
        shares.fill(0.0); energy.fill(0.0); onset.fill(0.0)
    }

    private fun rhythm(): Pair<Float, Float> {
        if (blocks < 150 || attacks < 4) return 0f to 0f
        val mean = onset.take(blocks).average()
        var best = 0.0
        var lag = 0
        // 65–190 BPM, at 50 analysis blocks per second. Normalize both sides.
        for (d in 16..46) {
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (i in d until blocks) {
                val x = onset[i] - mean; val y = onset[i - d] - mean
                xy += x * y; xx += x * x; yy += y * y
            }
            val score = if (xx * yy > 1e-12) xy / sqrt(xx * yy) else 0.0
            if (score > best + 0.025) { best = score; lag = d }
        }
        return if (best >= 0.45 && lag > 0) (3000f / lag) to best.toFloat() else 0f to 0f
    }

    private fun result(): CapsuleTrackSound {
        if (audible == 0) return CapsuleTrackSound.NEUTRAL
        val reference = doubleArrayOf(0.23, 0.26, 0.22, 0.20, 0.09)
        val density = (attacks * 50.0 / audible / 7.0 + activity / audible * 0.55).coerceIn(0.0, 1.0)
        val dynamics = ((crest / audible - 1.4) / 3.0).coerceIn(0.0, 1.0)
        val authored = shares.mapIndexed { i, sum ->
            val share = sum / audible
            val variance = (energy[i] / audible - share * share).coerceAtLeast(0.0)
            val stability = (1.0 - 1.8 * sqrt(variance)).coerceIn(0.35, 1.0)
            val imbalance = 10 * log10((share + 0.012) / (reference[i] + 0.012))
            val support = (share / reference[i]).coerceIn(0.0, 1.0)
            val correction = if (imbalance > 0) -80 * imbalance * stability else -92 * imbalance * support * stability
            // Add audible musical contour without lifting empty bands or sub/air extremes.
            val character = when (i) {
                1 -> 95 * support * (1.0 - density)
                2 -> 85 * support
                3 -> 115 * support * (1.0 - 0.55 * density)
                else -> 0.0
            }
            val ceiling = if (i == 0 || i == 4) 35.0 else 225.0
            val value = correction + character * (0.7 + 0.3 * dynamics)
            (value.coerceIn(-420.0, ceiling) / 5.0).roundToInt() * 5
        }
        val (bpm, confidence) = rhythm()
        return CapsuleTrackSound(capsuleMusicalEqLevels(authored), density.toFloat(), bpm, confidence)
    }
}

private class AnalysisBand(hz: Double, rate: Int, channels: Int) {
    private val enabled = hz < rate * 0.46
    private val w = 2 * PI * minOf(hz, rate * 0.45) / rate
    private val alpha = sin(w) / (2 * 0.65)
    private val b = alpha / (1 + alpha)
    private val a1 = -2 * cos(w) / (1 + alpha)
    private val a2 = (1 - alpha) / (1 + alpha)
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)
    fun process(x: Double, ch: Int): Double {
        if (!enabled) return 0.0
        val y = b * x + z1[ch]
        z1[ch] = -a1 * y + z2[ch]
        z2[ch] = -b * x - a2 * y
        return y
    }
}
