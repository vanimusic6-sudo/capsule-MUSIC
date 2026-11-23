/*
 * Capsule MUSIC — adaptive musical contour for Android effect bands, GPL-3.0.
 * This code does NOT estimate sound-pressure level or protect every speaker.
 * Never run per audio sample; prepare this on EQ/settings changes only.
 */
package com.nikhil.yt.playback

import kotlin.math.abs
import kotlin.math.roundToInt

internal const val CAPSULE_MUSICAL_MAX_SPREAD_MB = 700
internal const val CAPSULE_MUSICAL_MAX_NEIGHBOR_MB = 375

internal fun capsuleMusicalEqLevels(
    rawLevelsMb: List<Int>,
    centerFreqHz: List<Int> = emptyList(),
): List<Int> {
    if (rawLevelsMb.isEmpty() || rawLevelsMb.all { it == 0 }) return rawLevelsMb
    val count = rawLevelsMb.size
    val minimum = rawLevelsMb.minOrNull()!!.toLong()
    val maximum = rawLevelsMb.maxOrNull()!!.toLong()
    // A uniform offset is just a volume boost/cut, not an equalizer flavour.
    // Otherwise DO NOT subtract a median from gentle authored curves.
    val sharedGain = if (maximum - minimum <= 40L)
        ((maximum + minimum) / 2L) else 0L

    fun hz(i: Int): Int =
        centerFreqHz.getOrNull(i)?.takeIf { it in 20..20_000 }
            ?: if (count == 1) 1_500 else
                (80.0 * Math.pow(10_000.0 / 80.0,
                    i.toDouble() / (count - 1))).roundToInt()
    fun bounds(i: Int): IntRange = when (hz(i)) {
        in 20..120 -> -340..220   // extra care for excursion-heavy subbass
        in 121..650 -> -400..350
        in 651..4500 -> -420..320
        in 4501..8000 -> -350..300
        else -> -350..260
    }
    fun bound(i: Int, value: Int): Int {
        val range = bounds(i)
        return value.coerceIn(range.first, range.last)
    }
    val shaped = IntArray(count) { i ->
        val raw = (rawLevelsMb[i].toLong() - sharedGain)
            .coerceIn(-1500L, 1500L).toInt()
        bound(i, raw)
    }
    if (count == 1) return shaped.toList()

    // Only severe LOCAL peaks/notches are softened. Broadband bass or
    // smooth vocals are deliberately not smoothed on every application.
    val original = shaped.clone()
    for (i in 1 until count - 1) {
        val nearby = (original[i-1] + original[i+1]) / 2
        val excess = original[i] - nearby
        val leftDifference = original[i] - original[i-1]
        val rightDifference = original[i] - original[i+1]
        // A broad bass shelf next to a deep 910Hz notch is NOT itself a
        // narrow peak. Protect the isolated notch without flattening bass.
        val isolated = leftDifference.toLong()*rightDifference.toLong() > 0L &&
            minOf(abs(leftDifference),abs(rightDifference)) > 180 &&
            abs(original[i-1]-original[i+1]) < 300
        if (isolated && abs(excess) > 220) {
            val soft = 220 + ((abs(excess) - 220) * 0.25f).roundToInt()
            val limited = nearby + (if (excess > 0) soft else -soft)
            shaped[i] = bound(i, limited)
        }
    }

    // Extreme overall contrast gets a bounded wide-range projection; ordinary
    // profiles at or below 7 dB spread keep their intended bass-vocal ratio.
    val low = shaped.minOrNull() ?: 0
    val high = shaped.maxOrNull() ?: 0
    if (high - low > CAPSULE_MUSICAL_MAX_SPREAD_MB) {
        val middle = (low + high) / 2
        val floor = middle - CAPSULE_MUSICAL_MAX_SPREAD_MB / 2
        for (i in shaped.indices)
            shaped[i] = bound(i, shaped[i].coerceIn(
                floor, floor + CAPSULE_MUSICAL_MAX_SPREAD_MB))
    }

    // Correct only excessive neighbour jumps, gently moving BOTH sides.
    repeat(8) {
        for (direction in 0..1) {
            val indices = if (direction == 0) (0 until count-1)
                else (count-2 downTo 0)
            for (i in indices) {
                val delta = shaped[i+1] - shaped[i]
                if (abs(delta) <= CAPSULE_MUSICAL_MAX_NEIGHBOR_MB) continue
                val movement = (abs(delta) - CAPSULE_MUSICAL_MAX_NEIGHBOR_MB + 1)/2
                val sign = if (delta > 0) 1 else -1
                shaped[i] = bound(i, shaped[i]+movement*sign)
                shaped[i+1] = bound(i+1, shaped[i+1]-movement*sign)
            }
        }
    }
    return shaped.toList()
}

internal fun capsuleEqCurveSpreadMb(levels: List<Int>): Int =
    if (levels.isEmpty()) 0
    else (levels.maxOrNull()!!.toLong()-levels.minOrNull()!!.toLong())
        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/** Dynamic digital margin, never calibrated in dBA or speaker excursion. */
internal fun capsuleAdaptiveEqHeadroomMarginMb(curveMb: List<Int>): Int {
    val positive = (curveMb.maxOrNull() ?: 0).coerceAtLeast(0)
    if (positive == 0) return 0
    val steepest = curveMb.zipWithNext().maxOfOrNull { (a,b) ->
        kotlin.math.abs(b.toLong()-a.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } ?: 0
    // A gentle +0.5 dB shelf should not trigger the same trim as a +3.5 dB
    // peak next to a notch. Saturate at the previous conservative reserve.
    return (25L + positive.toLong()/3L + steepest.toLong()/8L)
        .coerceIn(35L, CAPSULE_EQ_HEADROOM_MARGIN_MB.toLong()).toInt()
}
