/* Capsule MUSIC — adaptive musical equalization. GPL-3.0. */
package com.nikhil.yt.playback

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

internal const val CAPSULE_EQ_MIN_MB = -500
internal const val CAPSULE_EQ_MAX_MB = 360
internal const val CAPSULE_EQ_MAX_CONTRAST_MB = 750
internal const val CAPSULE_EQ_MAX_STEP_MB = 375

internal fun capsuleStudioBandLimit(hz: Int): IntRange = when {
    hz < 70 -> -320..115
    hz < 130 -> -420..240
    hz <= 620 -> CAPSULE_EQ_MIN_MB..CAPSULE_EQ_MAX_MB
    hz <= 2_100 -> -425..340
    hz <= 3_800 -> -400..280
    else -> -320..200
}

/** Preserve broad changes and soften sharp holes. The PCM stage reserves actual filter headroom. */
internal fun capsuleMusicalEqLevels(rawLevelsMb: List<Int>): List<Int> {
    val frequencies = CAPSULE_STUDIO_FREQUENCIES_HZ
    val levels = resampleEqLevelsByIndex(rawLevelsMb, frequencies.size)
    if (levels.all { it == levels.first() }) return List(frequencies.size) { 0 }

    val limits = frequencies.map(::capsuleStudioBandLimit)
    val curve = IntArray(levels.size) { levels[it].coerceIn(limits[it]) }
    fun limitNotch(i: Int) {
        val left = frequencies[i - 1].toDouble()
        val fraction = ln(frequencies[i] / left) / ln(frequencies[i + 1] / left)
        val expected = curve[i - 1] + (curve[i + 1] - curve[i - 1]) * fraction
        // Keep meaningful narrow boosts and prevent a deep hole between two boosts.
        val delta = curve[i] - expected
        val knee = delta.coerceIn(-240.0, 295.0)
        curve[i] = (expected + knee + (delta - knee) * 0.18).roundToInt().coerceIn(limits[i])
    }
    fun limitStep(i: Int) {
        val delta = curve[i + 1] - curve[i]
        if (abs(delta) <= CAPSULE_EQ_MAX_STEP_MB) return
        val excess = abs(delta) - CAPSULE_EQ_MAX_STEP_MB
        val correction = (excess + 1) / 2
        val direction = if (delta > 0) 1 else -1
        curve[i] = (curve[i] + correction * direction).coerceIn(limits[i])
        curve[i + 1] = (curve[i + 1] - correction * direction).coerceIn(limits[i + 1])
    }
    for (i in 1 until curve.lastIndex) limitNotch(i)
    repeat(4) {
        for (i in 0 until curve.lastIndex) limitStep(i)
        for (i in curve.lastIndex - 1 downTo 0) limitStep(i)
    }
    // A final propagation bounds rounding residue from successive adjustments.
    for (i in 1..curve.lastIndex) {
        curve[i] = curve[i].coerceIn(curve[i - 1] - CAPSULE_EQ_MAX_STEP_MB,
            curve[i - 1] + CAPSULE_EQ_MAX_STEP_MB)
    }
    val low = curve.min()
    val high = curve.max()
    val spread = high - low
    if (spread <= CAPSULE_EQ_MAX_CONTRAST_MB) return curve.toList()
    val middle = (high + low) / 2.0
    val scale = CAPSULE_EQ_MAX_CONTRAST_MB.toDouble() / spread
    return curve.mapIndexed { i, level ->
        (middle + (level - middle) * scale).roundToInt().coerceIn(limits[i])
    }
}
