/*
 * Capsule MUSIC — ear-friendly graphic EQ policy, GPL-3.0.
 *
 * Raw user-selected gains are never modified or written back. This function
 * shapes ONLY effective gains supplied to native or unified DSP.
 *
 * A reasonable relative spectrum reduces harshness; it is NOT a headphone
 * sound-pressure meter and cannot guarantee safe listening levels.
 */
package com.nikhil.yt.playback

import kotlin.math.abs
import kotlin.math.roundToInt

internal const val CAPSULE_MUSICAL_MAX_SPREAD_MB = 750     // 8.5 dB between extremes
internal const val CAPSULE_MUSICAL_MAX_NEIGHBOR_MB = 410   // 5 dB across adjacent bands
private const val MUSICAL_SOFT_KNEE_MB = 240

/** Softly bend extreme EQ profiles back toward a musical tonal balance. */
internal fun capsuleMusicalEqLevels(
    rawLevelsMb: List<Int>,
    centerFreqHz: List<Int> = emptyList(),
): List<Int> {
    if (rawLevelsMb.isEmpty()) return emptyList()
    if (rawLevelsMb.all { it == 0 }) return rawLevelsMb

    val count = rawLevelsMb.size
    // A uniform boost is mostly extra volume, not better tonality. Removing a
    // small common offset leaves actual relative EQ adjustments intact.
    val sorted = rawLevelsMb.sorted()
    val median = sorted[count / 2].coerceAtLeast(0)
    val commonPositiveMb = (median * 0.40f).roundToInt().coerceAtMost(250)

    val highs = IntArray(count) { index ->
        val hz = centerFreqHz.getOrNull(index)?.takeIf { it > 0 }
            ?: if (index == 0) 60 else if (count == 1) 910 else
                (60.0 * Math.pow(14000.0 / 60.0,
                    index.toDouble() / (count - 1))).roundToInt()
        when {
            hz <= 120 -> 350
            hz <= 700 -> 620
            hz <= 2_400 -> 540
            hz <= 7_000 -> 500
            else -> 410
        }
    }
    val lows = IntArray(count) { index ->
        val hz = centerFreqHz.getOrNull(index)?.takeIf { it > 0 } ?: 910
        if (hz in 700..4_500) -480 else -650
    }
    fun bound(index: Int, gainMb: Int): Int =
        gainMb.coerceIn(lows[index], highs[index])

    fun softKnee(gainMb: Int): Int {
        val magnitude = abs(gainMb.toLong()).coerceAtMost(10_000L).toInt()
        if (magnitude <= MUSICAL_SOFT_KNEE_MB) return gainMb
        val softened = MUSICAL_SOFT_KNEE_MB +
            ((magnitude - MUSICAL_SOFT_KNEE_MB) * 0.83f).roundToInt()
        return if (gainMb < 0) -softened else softened
    }
    val safe = IntArray(count) { index ->
        // Imported JSON and malformed legacy presets may contain Int.MIN_VALUE;
        // subtracting the common offset as Int would wrap into a giant BOOST.
        val shifted = (rawLevelsMb[index].toLong() - commonPositiveMb.toLong())
            .coerceIn(-10_000L, 10_000L).toInt()
        bound(index, softKnee(bound(index, shifted)))
    }
    if (count == 1) return safe.toList()

    // One low-cost nearest-neighbour pass avoids abrupt, over-resonant notches.
    // Hold each band at 82% of its own request, distributing the remainder to
    // adjacent regions, preserving the difference between their controls.
    val smoothed = IntArray(count) { index ->
        val left = safe[(index - 1).coerceAtLeast(0)]
        val right = safe[(index + 1).coerceAtMost(count - 1)]
        bound(index, (safe[index] * 0.82f + (left + right) * 0.09f).roundToInt())
    }
    // A full-spectrum contrast limit prevents exaggerated V/notch profiles
    // even when two peaks are NOT next to each other.
    val minimum = smoothed.minOrNull() ?: 0
    val maximum = smoothed.maxOrNull() ?: 0
    if (maximum - minimum > CAPSULE_MUSICAL_MAX_SPREAD_MB) {
        val center = (maximum.toLong() + minimum.toLong()) / 2L
        val lower = (center - CAPSULE_MUSICAL_MAX_SPREAD_MB / 2).toInt()
        val upper = lower + CAPSULE_MUSICAL_MAX_SPREAD_MB
        for (i in smoothed.indices) {
            smoothed[i] = bound(i, smoothed[i].coerceIn(lower, upper))
        }
    }
    // Projection on adjacent differences. No DSP allocation or per-frame work:
    // the policy is run only when audio preferences / actual hardware change.
    repeat(6) {
        for (i in 0 until count - 1) {
            val difference = smoothed[i + 1] - smoothed[i]
            if (abs(difference) > CAPSULE_MUSICAL_MAX_NEIGHBOR_MB) {
                val correction = ((abs(difference) - CAPSULE_MUSICAL_MAX_NEIGHBOR_MB)
                    / 2f).roundToInt()
                val direction = if (difference > 0) 1 else -1
                smoothed[i] = bound(i, smoothed[i] + correction * direction)
                smoothed[i + 1] = bound(i + 1, smoothed[i + 1] - correction * direction)
            }
        }
        for (i in count - 2 downTo 0) {
            val difference = smoothed[i + 1] - smoothed[i]
            if (abs(difference) > CAPSULE_MUSICAL_MAX_NEIGHBOR_MB) {
                val correction = ((abs(difference) - CAPSULE_MUSICAL_MAX_NEIGHBOR_MB)
                    / 2f).roundToInt()
                val direction = if (difference > 0) 1 else -1
                smoothed[i] = bound(i, smoothed[i] + correction * direction)
                smoothed[i + 1] = bound(i + 1, smoothed[i + 1] - correction * direction)
            }
        }
    }
    return smoothed.toList()
}

/** This measures relative curve shape, not electrical/acoustic loudness. */
internal fun capsuleEqCurveSpreadMb(levels: List<Int>): Int =
    if (levels.isEmpty()) 0
    else (levels.maxOrNull()!!.toLong() - levels.minOrNull()!!.toLong())
        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
