/*
 * Capsule Music — ten musical bands, independent of vendor Equalizer presets.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This is a DIGITAL equalizer, not a calibrated sound-pressure monitor.
 */
package com.nikhil.yt.playback

import kotlin.math.ln
import kotlin.math.roundToInt

/** Musical, logarithmic control points, not "safe" acoustic frequencies. */
internal val CAPSULE_TEN_BAND_HZ = listOf(
    80, 160, 315, 630, 1_250, 2_500, 4_000, 6_300, 10_000, 16_000,
)

/** DynamicsProcessing is configured with exactly these ten bands on API 28+. */
internal fun capsuleTenBandCapabilities(): EqCapabilities = EqCapabilities(
    bandCount = CAPSULE_TEN_BAND_HZ.size,
    minBandLevelMb = -1500,
    maxBandLevelMb = 1500,
    centerFreqHz = CAPSULE_TEN_BAND_HZ,
    systemPresets = emptyList(),
)

/**
 * Interpolate a legacy profile / a ten-band curve onto the actual Android
 * fallback's frequencies, never by arbitrary band index. This only uses
 * bounded lists and log-frequency interpolation when frequency metadata is
 * valid, otherwise it falls back to a monotonic log-frequency grid.
 *
 * Source profiles: 10 = current logical editor; 7 = earlier curated;
 * 5 = original Android five-point graph. Other sizes use a log grid.
 */
internal fun capsuleProjectEqLevelsByFrequency(
    sourceLevelsMb: List<Int>,
    targetCenterHz: List<Int>,
): List<Int> {
    if (targetCenterHz.isEmpty()) return emptyList()
    if (sourceLevelsMb.isEmpty()) return List(targetCenterHz.size) { 0 }
    val source = sourceLevelsMb.take(32).map { it.coerceIn(-1500, 1500) }
    val frequencyGrid = when (source.size) {
        10 -> CAPSULE_TEN_BAND_HZ
        7 -> listOf(80, 200, 500, 1_300, 3_400, 8_000, 14_000)
        5 -> listOf(60, 230, 910, 3_600, 14_000)
        else -> List(source.size) { i ->
            if (source.size == 1) 1_000
            else (80.0 * Math.pow(16_000.0 / 80.0,
                i.toDouble() / (source.size - 1))).roundToInt()
        }
    }
    val validTargets = targetCenterHz.all { it in 20..20_000 } &&
        targetCenterHz.zipWithNext().all { (a, b) -> a < b }
    return targetCenterHz.mapIndexed { index, target ->
        val hz = if (validTargets) target.toDouble()
            else if (targetCenterHz.size == 1) 1_000.0
            else 80.0 * Math.pow(16_000.0 / 80.0,
                index.toDouble() / (targetCenterHz.size - 1))
        val left = frequencyGrid.indexOfLast { it <= hz }
            .coerceIn(0, frequencyGrid.lastIndex)
        when {
            left == frequencyGrid.lastIndex -> source.last()
            hz <= frequencyGrid.first() -> source.first()
            else -> {
                val low = ln(frequencyGrid[left].toDouble())
                val high = ln(frequencyGrid[left + 1].toDouble())
                val blend = ((ln(hz) - low) / (high - low)).coerceIn(0.0, 1.0)
                (source[left] * (1 - blend) + source[left + 1] * blend).roundToInt()
            }
        }
    }
}
