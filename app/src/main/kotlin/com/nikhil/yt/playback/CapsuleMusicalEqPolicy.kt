/*
 * Capsule MUSIC — adaptive musical equalization, GPL-3.0.
 * No "safe frequencies" exist for all listeners and transducers. This guards
 * against excessive DIGITAL contrast, abrupt neighbouring bands and clipping;
 * it does not measure dBA, driver excursion or cumulative exposure.
 * Pure band math, computed only when a profile changes — not per audio frame.
 */
package com.nikhil.yt.playback

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

internal const val CAPSULE_MUSICAL_MAX_SPREAD_MB = 680
internal const val CAPSULE_MUSICAL_MAX_NEIGHBOR_MB = 380
private const val CAPSULE_ADAPTIVE_MIN_GAP_MB = 160

/** A gentle curve is left alone. Only excessive peaks, narrow pits and
 *  large inter-band differences trigger progressively stronger adaptation. */
internal fun capsuleMusicalEqLevels(
    rawLevelsMb: List<Int>, centerFreqHz: List<Int> = emptyList(),
): List<Int> {
    if (rawLevelsMb.isEmpty() || rawLevelsMb.all { it == 0 }) return rawLevelsMb
    val n = rawLevelsMb.size
    val sorted = rawLevelsMb.sorted()
    val spread = sorted.last().toLong() - sorted.first().toLong()
    val median = sorted[n / 2]
    // Uniform gains only alter loudness. Remove common positive or negative
    // offsets without changing relative tonal differences.
    val common = when {
        spread <= 50L -> median
        sorted.first() > 0 -> sorted.first()
        sorted.last() < 0 -> sorted.last()
        else -> 0
    }

    // If OEM frequencies are missing, duplicated or scrambled, use a complete
    // monotonic logarithmic fallback; never mix unrelated coordinate systems.
    val validMap = centerFreqHz.size == n &&
        centerFreqHz.all { it in 20..20_000 } &&
        centerFreqHz.zipWithNext().all { (a, b) -> a < b }
    val hz = DoubleArray(n) { index ->
        if (validMap) centerFreqHz[index].toDouble()
        else if (n == 1) 1_000.0 else
            80.0 * Math.pow(14_000.0 / 80.0, index.toDouble() / (n - 1))
    }
    fun limit(i: Int): IntRange = when {
        hz[i] <= 120.0 -> -340..220    // cautious with small speaker excursion
        hz[i] <= 650.0 -> -460..320
        hz[i] <= 4500.0 -> -430..300
        hz[i] <= 8000.0 -> -380..260
        else -> -330..200
    }
    fun clamp(i: Int, value: Int): Int {
        val bounds=limit(i)
        return value.coerceIn(bounds.first,bounds.last)
    }
    val result = IntArray(n) { i ->
        clamp(i, (rawLevelsMb[i].toLong()-common.toLong())
            .coerceIn(-2500L,2500L).toInt())
    }
    if (n == 1) return result.toList()

    // In contrast with old blanket 0.84x smoothing, don't touch a natural
    // broad contour. Only a *narrow* local deviation from its neighbours
    // is pulled toward log-frequency interpolation.
    repeat(6) {
        for (i in 1 until n-1) {
            val leftX=ln(hz[i-1])
            val centreX=ln(hz[i])
            val rightX=ln(hz[i+1])
            val proportion=if (rightX > leftX)
                ((centreX-leftX)/(rightX-leftX)).coerceIn(0.0,1.0) else 0.5
            val expectation=(result[i-1]*(1.0-proportion)+
                result[i+1]*proportion).roundToInt()
            val deviation=(result[i].toLong()-expectation).coerceIn(-3000,3000).toInt()
            // Upper mids are particularly revealing of a "vacuum" caused by
            // a sharp dip. Limits operate on contour shape, not named 910 Hz.
            val allowed=if (hz[i] in 500.0..5000.0) 210 else 280
            if (abs(deviation)>allowed) {
                // Retain the first ~2 dB of musical curvature; reduce only
                // extreme localized spikes/pits, with a gentle soft knee.
                val excess=abs(deviation)-allowed
                val knee=allowed+(excess*0.15f).roundToInt()
                result[i]=clamp(i,expectation+if (deviation>0) knee else -knee)
            }
        }
        // Neighbour continuity adapts to device frequency density: 10-band
        // devices get tighter per-step limits than widely spaced 5-band EQs.
        for (reverse in 0..1) {
            val steps=if(reverse==0)(0 until n-1) else(n-2 downTo 0)
            for(i in steps) {
                val octaveDistance=if(hz[i+1]>hz[i])
                    ln(hz[i+1]/hz[i])/ln(2.0) else 1.0
                val allowed=(175+octaveDistance*92).roundToInt()
                    .coerceIn(CAPSULE_ADAPTIVE_MIN_GAP_MB,CAPSULE_MUSICAL_MAX_NEIGHBOR_MB)
                val diff=result[i+1]-result[i]
                if(abs(diff)>allowed) {
                    val correction=(abs(diff)-allowed+1)/2
                    val direction=if(diff>0)1 else -1
                    result[i]=clamp(i,result[i]+correction*direction)
                    result[i+1]=clamp(i+1,result[i+1]-correction*direction)
                }
            }
        }
    }
    // Final global contrast check applies only to extreme overall curves,
    // with proportional scaling rather than arbitrary frequency clipping.
    val lowest=result.minOrNull()?:0
    val highest=result.maxOrNull()?:0
    val fullSpread=highest-lowest
    if(fullSpread>CAPSULE_MUSICAL_MAX_SPREAD_MB) {
        val midpoint=(lowest+highest)/2f
        val factor=CAPSULE_MUSICAL_MAX_SPREAD_MB.toFloat()/fullSpread
        for(i in result.indices) result[i]=clamp(i,
            (midpoint+(result[i]-midpoint)*factor).roundToInt())
    }
    return result.toList()
}

internal fun capsuleEqCurveSpreadMb(levels: List<Int>): Int =
    if(levels.isEmpty()) 0 else
        (levels.maxOrNull()!!.toLong()-levels.minOrNull()!!.toLong())
            .coerceIn(0L,Int.MAX_VALUE.toLong()).toInt()
