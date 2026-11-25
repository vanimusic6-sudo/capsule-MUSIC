/* Capsule MUSIC — per-track PCM analysis. GPL-3.0. */
package com.nikhil.yt.playback

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class CapsuleTempoShape(val speed: Float, val pitch: Float)

internal data class CapsuleTrackSound(val levelsMb: List<Int>, val pace: Float) {
    fun tempo(variant: SoundStudioTempoVariant): CapsuleTempoShape {
        val density = if (pace.isFinite()) pace.coerceIn(0f, 1f) else 0.76f
        val speed = when (variant) {
            SoundStudioTempoVariant.NORMAL -> 1f
            SoundStudioTempoVariant.SPEED_UP -> 1.20f - 0.09f * density
            SoundStudioTempoVariant.SLOWED -> 0.84f + 0.08f * density
        }
        // Media3 stretches time by speed / pitch. Coupled values use only
        // resampling and preserve the timing relationships inside the mix.
        return CapsuleTempoShape(speed, speed)
    }

    /** A small mastering curve, independent of the user's EQ and mixer. */
    fun versionLevels(variant: SoundStudioTempoVariant): List<Int> {
        if (variant == SoundStudioTempoVariant.NORMAL) return emptyList()
        val density = if (pace.isFinite()) pace.coerceIn(0f, 1f) else 0.76f
        val voicing = when (variant) {
            SoundStudioTempoVariant.SPEED_UP -> listOf(-110, -45, 55, 145, -65)
            SoundStudioTempoVariant.SLOWED -> listOf(-90, 145, 65, -105, -165)
            SoundStudioTempoVariant.NORMAL -> return emptyList()
        }
        val strength = 1.0 - 0.35 * density
        return voicing.mapIndexed { band, level ->
            // Only apply the subtractive part of the source correction here;
            // adaptive EQ, if selected, remains its own independent layer.
            val excess = levelsMb.getOrElse(band) { 0 }.coerceIn(-350, 0)
            (level * strength + 0.20 * excess).roundToInt()
        }
    }

    companion object {
        val NEUTRAL = CapsuleTrackSound(emptyList(), 0.76f)
    }
}

/** Analyze a bounded audible excerpt once. Each band has its own energy measurement. */
internal class CapsuleStudioAnalysis(private val sampleRate: Int, private val channels: Int) {
    private val steps = listOf(150.0, 635.0, 1_550.0, 5_900.0).map {
        1.0 - exp(-2.0 * PI * minOf(it, sampleRate * 0.32) / sampleRate)
    }.toDoubleArray()
    private val filters = DoubleArray(channels * steps.size)
    private val blockEnergy = DoubleArray(steps.size + 1)
    private val energy = DoubleArray(steps.size + 1)
    private val blockSize = (sampleRate / 50).coerceAtLeast(1)
    private var frames = 0
    private var blocks = 0
    private var audibleBlocks = 0
    private var blockPower = 0.0
    private var envelope = 0.0
    private var previous = 0.0
    private var attacks = 0
    private var lastAttack = -10
    private var activity = 0.0

    fun observe(input: ByteBuffer): CapsuleTrackSound? {
        val frameBytes = channels * Short.SIZE_BYTES
        var index = input.position()
        while (index + frameBytes <= input.limit()) {
            for (channel in 0 until channels) {
                val x = input.getShort(index) / 32768.0
                index += Short.SIZE_BYTES
                blockPower += x * x
                var last = 0.0
                for (band in steps.indices) {
                    val slot = channel * steps.size + band
                    filters[slot] += steps[band] * (x - filters[slot])
                    val value = filters[slot] - last
                    blockEnergy[band] += value * value
                    last = filters[slot]
                }
                val upper = x - last
                blockEnergy[steps.size] += upper * upper
            }
            if (++frames != blockSize) continue
            finishBlock()
            if (audibleBlocks >= 210 || blocks >= 600 && audibleBlocks >= 60) return result()
            if (blocks >= 600) {
                blocks = 0
                audibleBlocks = 0
                lastAttack = -10
                attacks = 0
                activity = 0.0
                energy.fill(0.0)
            }
        }
        return null
    }

    private fun finishBlock() {
        val rms = sqrt(blockPower / (blockSize * channels))
        blocks++
        if (rms > 0.006) {
            audibleBlocks++
            // Cap each block's contribution so a loud hit cannot set the track EQ.
            val weight = minOf(1.0, 0.0484 / (rms * rms))
            for (band in energy.indices) energy[band] += blockEnergy[band] * weight
            if (rms > maxOf(0.018, envelope * 1.32) && rms - previous > maxOf(0.009, envelope * 0.16) &&
                blocks - lastAttack >= 4
            ) {
                attacks++
                lastAttack = blocks
            }
            activity += (abs(rms - previous) / maxOf(0.035, envelope)).coerceAtMost(1.0)
        }
        envelope += 0.14 * (rms - envelope)
        previous = rms
        frames = 0
        blockPower = 0.0
        blockEnergy.fill(0.0)
    }

    private fun result(): CapsuleTrackSound {
        val reference = doubleArrayOf(0.27, 0.27, 0.19, 0.19, 0.08)
        val total = energy.sum()
        if (!total.isFinite() || total <= 0.0) return CapsuleTrackSound.NEUTRAL
        val authored = energy.mapIndexed { band, power ->
            val share = power / total
            val imbalance = 10 * log10((share + 0.012) / (reference[band] + 0.012))
            // A missing band is left alone. Only cut excess or gently support audible content.
            val support = (share / reference[band]).coerceIn(0.0, 1.0)
            val correction = if (imbalance > 0.0) -64.0 * imbalance else -68.0 * imbalance * support
            val ceiling = if (band == 0 || band == energy.lastIndex) 45.0 else 180.0
            ((correction.coerceIn(-350.0, ceiling) / 5.0).roundToInt() * 5)
        }
        val density = attacks * 50.0 / audibleBlocks
        val pace = (density / 7.0 + activity / audibleBlocks * 0.55).coerceIn(0.0, 1.0)
        return CapsuleTrackSound(capsuleMusicalEqLevels(authored), pace.toFloat())
    }
}
