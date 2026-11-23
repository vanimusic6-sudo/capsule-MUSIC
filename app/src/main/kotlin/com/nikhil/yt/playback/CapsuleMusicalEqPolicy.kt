/*
 * Capsule MUSIC — adaptive musical contour (GPL-3.0).
 * Shape regularisation, NOT calibrated SPL, speaker excursion, or true-peak analysis.
 * Runs only when settings change, never on the audio callback thread.
 */
package com.nikhil.yt.playback

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

internal const val CAPSULE_MUSICAL_MAX_SPREAD_MB = 700
internal const val CAPSULE_MUSICAL_MAX_NEIGHBOR_MB = 375

/**
 * The user chooses the tonal shape. Wide, smooth contours keep their character;
 * narrow, sharply bent notches/peaks are automatically softened in log-Hz space.
 * All gains are bounded before they reach any Android DSP implementation.
 */
internal fun capsuleMusicalEqLevels(
    rawLevelsMb: List<Int>, centerFreqHz: List<Int> = emptyList(),
): List<Int> {
    val n = rawLevelsMb.size
    if (n == 0 || rawLevelsMb.all { it == 0 }) return rawLevelsMb
    if (n == 1) return listOf(0) // one band can only change loudness, not flavour

    val frequencies = DoubleArray(n) { index ->
        centerFreqHz.getOrNull(index)?.takeIf { it in 20..20_000 }?.toDouble()
            ?: 70.0 * Math.pow(13_000.0 / 70.0, index.toDouble() / (n - 1))
    }
    // OEMs occasionally report duplicate or non-monotonic centres. Use a safe,
    // strictly increasing log grid, rather than dividing by zero or NaN.
    for (i in 1 until n) {
        if (frequencies[i] <= frequencies[i - 1])
            frequencies[i] = frequencies[i - 1] * 1.12
    }
    fun limits(i: Int): IntRange = when {
        frequencies[i] < 115.0 -> -340..220 // sub-bass excursion precaution
        frequencies[i] < 5_000.0 -> -420..350
        else -> -350..260
    }
    fun bound(i: Int, mb: Int) = mb.coerceIn(limits(i).first, limits(i).last)
    val sorted = rawLevelsMb.sorted()
    val spread = sorted.last().toLong() - sorted.first().toLong()
    // Uniform positive gain does not add musical taste. Remove it instead.
    val common = if (spread <= 50L) sorted[n / 2].toLong()
        else sorted[n / 2].toLong().coerceAtLeast(0L) * 40L / 100L
    val result = IntArray(n) { i ->
        val requested = (rawLevelsMb[i].toLong() - common)
            .coerceIn(-10_000L, 10_000L).toInt()
        bound(i, requested)
    }

    // A narrow notch differs from a broad bass shelf. Detect local *curvature*
    // in octaves, rather than banning a named frequency like 910 Hz.
    val snapshot = result.copyOf()
    for (i in 1 until n - 1) {
        val a = ln(frequencies[i - 1])
        val b = ln(frequencies[i])
        val c = ln(frequencies[i + 1])
        val weight = ((b - a) / (c - a)).coerceIn(0.0, 1.0)
        val expected = snapshot[i - 1] * (1.0 - weight) + snapshot[i + 1] * weight
        val deviation = snapshot[i] - expected
        val knee = 125.0
        if (kotlin.math.abs(deviation) > knee) {
            val corrected = expected +
                kotlin.math.sign(deviation) * (knee + (kotlin.math.abs(deviation) - knee) * 0.34)
            result[i] = bound(i, corrected.roundToInt())
        }
    }

    // Smooth legitimate broad profiles remain almost untouched; over-steep
    // adjacent slopes are redistributed, not hard-clipped to an overall level.
    fun allowedJump(index: Int): Int {
        val octaves = (ln(frequencies[index + 1] / frequencies[index]) / ln(2.0))
            .coerceIn(0.0, 5.0)
        return (65.0 + octaves * 145.0).roundToInt()
            .coerceIn(100, CAPSULE_MUSICAL_MAX_NEIGHBOR_MB)
    }
    repeat(12) {
        for (direction in 0..1) {
            val order = if (direction == 0) 0 until n - 1 else n - 2 downTo 0
            for (i in order) {
                val difference = result[i + 1] - result[i]
                val allowed = allowedJump(i)
                if (abs(difference) <= allowed) continue
                val correction = (abs(difference) - allowed + 1) / 2
                val sign = if (difference > 0) 1 else -1
                result[i] = bound(i, result[i] + correction * sign)
                result[i + 1] = bound(i + 1, result[i + 1] - correction * sign)
            }
        }
    }
    // A very broad shelving request can obey every *local* slope yet still
    // produce an extreme bass-to-treble contrast. Intervene only above 7 dB.
    val minimum = result.minOrNull() ?: 0
    val maximum = result.maxOrNull() ?: 0
    if (maximum.toLong() - minimum.toLong() > CAPSULE_MUSICAL_MAX_SPREAD_MB) {
        val centre = (minimum + maximum) / 2
        val low = centre - CAPSULE_MUSICAL_MAX_SPREAD_MB / 2
        val high = low + CAPSULE_MUSICAL_MAX_SPREAD_MB
        for (i in result.indices) result[i] = bound(i, result[i].coerceIn(low, high))
    }
    return result.toList()
}

internal fun capsuleEqCurveSpreadMb(levels: List<Int>): Int =
    if (levels.isEmpty()) 0
    else (levels.maxOrNull()!!.toLong() - levels.minOrNull()!!.toLong())
        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
