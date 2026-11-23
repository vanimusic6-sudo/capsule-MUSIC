package com.nikhil.yt.playback

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Five gentle musical signatures over seven logarithmic reference points.
 * These are user-facing design frequencies, not claimed "safe" frequencies:
 * each device maps them to its actual available EQ bands.
 * No BassBoost, Virtualizer or global system changes.
 */
internal data class CapsuleEqPreset(
    val id: String,
    val name: String,
    val referenceLevelsMb: List<Int>,
)

internal object CapsuleEqPresets {
    private val referenceHz = listOf(80, 200, 500, 1_300, 3_400, 8_000, 14_000)

    // Mild, broad signatures. 100 millibels = 1 dB; no hard 910 Hz notch.
    val all = listOf(
        CapsuleEqPreset("soft_bass_v2", "Soft Bass",
            listOf(150, 190, 130, 50, 0, -40, -65)),
        CapsuleEqPreset("warm_v2", "Warm",
            listOf(90, 150, 130, 60, -20, -80, -100)),
        CapsuleEqPreset("vocal_v2", "Vocals",
            listOf(-70, -40, 0, 130, 140, 50, -10)),
        CapsuleEqPreset("air_v2", "Air",
            listOf(-60, -40, -20, 30, 80, 130, 150)),
        CapsuleEqPreset("live_v2", "Live",
            listOf(50, 80, 40, 80, 90, 80, 40)),
    )

    fun byId(id: String): CapsuleEqPreset? = all.firstOrNull { it.id == id }

    /**
     * Interpolate in log-frequency space, not by band index. A 3/5/10-band
     * device therefore receives the *same* audio signature at its own centers.
     * On a missing/invalid center-frequency map, fall back to evenly spaced
     * positions across the same logarithmic frequency range.
     */
    fun levelsFor(
        preset: CapsuleEqPreset,
        centerFreqHz: List<Int>,
        minMb: Int,
        maxMb: Int,
    ): List<Int> {
        if (centerFreqHz.isEmpty()) return emptyList()
        if (minMb > maxMb || preset.referenceLevelsMb.size != referenceHz.size) {
            return List(centerFreqHz.size) { 0 }
        }
        val last = centerFreqHz.lastIndex
        return centerFreqHz.mapIndexed { index, frequency ->
            val hz = frequency.takeIf { it in 20..20_000 }?.toDouble()
                ?: if (last == 0) 1_300.0 else {
                    val position = index.toDouble() / last
                    80.0 * (14_000.0 / 80.0).pow(position)
                }
            val anchor = referenceHz.indexOfLast { it <= hz }.coerceIn(0, referenceHz.lastIndex)
            val mb = when {
                anchor == referenceHz.lastIndex -> preset.referenceLevelsMb.last()
                hz <= referenceHz.first() -> preset.referenceLevelsMb.first()
                else -> {
                    val leftHz = ln(referenceHz[anchor].toDouble())
                    val rightHz = ln(referenceHz[anchor + 1].toDouble())
                    val t = ((ln(hz) - leftHz) / (rightHz - leftHz)).coerceIn(0.0, 1.0)
                    (preset.referenceLevelsMb[anchor] +
                        (preset.referenceLevelsMb[anchor + 1] -
                            preset.referenceLevelsMb[anchor]) * t).roundToInt()
                }
            }
            mb.coerceIn(minMb, maxMb)
        }
    }

    private fun Double.pow(exponent: Double): Double =
        kotlin.math.exp(kotlin.math.ln(this) * exponent)
}
