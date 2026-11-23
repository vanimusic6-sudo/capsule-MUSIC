/*
 * Capsule MUSIC — conservative musical contour, GPL-3.0.
 * Not an acoustic sound-pressure meter or a speaker excursion model.
 * Operates on band gains only; no audio-frame allocations.
 */
package com.nikhil.yt.playback

import kotlin.math.abs
import kotlin.math.roundToInt

internal const val CAPSULE_MUSICAL_MAX_SPREAD_MB = 700
internal const val CAPSULE_MUSICAL_MAX_NEIGHBOR_MB = 375

internal fun capsuleMusicalEqLevels(
    rawLevelsMb: List<Int>, centerFreqHz: List<Int> = emptyList(),
): List<Int> {
    if (rawLevelsMb.isEmpty() || rawLevelsMb.all { it == 0 }) return rawLevelsMb
    val count = rawLevelsMb.size
    val sorted = rawLevelsMb.sorted()
    val span = sorted.last().toLong() - sorted.first().toLong()
    val median = sorted[count / 2].toLong()
    // Broadband gain adds loudness, not tonal character.
    val common = if (span <= 50L) median else median.coerceAtLeast(0L) * 40L / 100L

    fun frequency(i: Int): Int =
        centerFreqHz.getOrNull(i)?.takeIf { it in 20..20_000 }
            ?: if (count == 1) 900 else (60.0 * Math.pow(14_000.0 / 60.0,
                i.toDouble() / (count - 1))).roundToInt()
    fun bounds(i: Int): IntRange = when (frequency(i)) {
        in 20..120 -> -340..220
        in 121..650 -> -400..350
        in 651..4500 -> -420..320
        in 4501..8000 -> -350..300
        else -> -350..260
    }
    fun clamp(i: Int, value: Int): Int {
        val range = bounds(i)
        return value.coerceIn(range.first, range.last)
    }
    val bounded = IntArray(count) { i ->
        val difference = (rawLevelsMb[i].toLong() - common)
            .coerceIn(-10_000L, 10_000L).toInt()
        val gain = difference.coerceIn(-1200, 1200)
        val softened = if (abs(gain) <= 160) gain else {
            val magnitude = 160 + ((abs(gain) - 160) * 0.84f).roundToInt()
            if (gain < 0) -magnitude else magnitude
        }
        clamp(i, softened)
    }
    if (count == 1) return bounded.toList()
    val result = IntArray(count) { i ->
        val left = bounded[(i - 1).coerceAtLeast(0)]
        val right = bounded[(i + 1).coerceAtMost(count - 1)]
        clamp(i, (bounded[i] * 0.86f + (left + right) * 0.07f).roundToInt())
    }
    val minimum = result.minOrNull() ?: 0
    val maximum = result.maxOrNull() ?: 0
    if (maximum - minimum > CAPSULE_MUSICAL_MAX_SPREAD_MB) {
        val mid = (minimum + maximum) / 2
        val lower = mid - CAPSULE_MUSICAL_MAX_SPREAD_MB / 2
        for (i in result.indices) result[i] =
            clamp(i, result[i].coerceIn(lower, lower + CAPSULE_MUSICAL_MAX_SPREAD_MB))
    }
    repeat(8) {
        for (direction in 0..1) {
            val indices = if (direction == 0) (0 until count - 1)
                else (count - 2 downTo 0)
            for (i in indices) {
                val diff = result[i + 1] - result[i]
                if (abs(diff) <= CAPSULE_MUSICAL_MAX_NEIGHBOR_MB) continue
                val correction = (abs(diff) - CAPSULE_MUSICAL_MAX_NEIGHBOR_MB + 1) / 2
                val directionSign = if (diff > 0) 1 else -1
                result[i] = clamp(i, result[i] + correction * directionSign)
                result[i + 1] = clamp(i + 1, result[i + 1] - correction * directionSign)
            }
        }
    }
    return result.toList()
}

internal fun capsuleEqCurveSpreadMb(levels: List<Int>): Int =
    if (levels.isEmpty()) 0
    else (levels.maxOrNull()!!.toLong() - levels.minOrNull()!!.toLong())
        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
