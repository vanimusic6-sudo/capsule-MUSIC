package com.nikhil.yt.playback

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Five carefully shaped broad signatures over ten logarithmic reference points.
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
    private val referenceHz = CAPSULE_STUDIO_FREQUENCIES_HZ

    // Broader signatures are more noticeable than the previous versions,
    // without narrow midrange holes or aggressive extreme-treble boosts.
    // All authored points remain below 2.5 dB; hardware mapping is logarithmic.
    val all = listOf(
        CapsuleEqPreset("soft_bass_v2", "Soft Bass",
            listOf(70, 150, 220, 245, 155, 25, -30, -75, -110, -140)),
        CapsuleEqPreset("warm_v2", "Warm",
            listOf(55, 110, 170, 205, 175, 75, -5, -55, -95, -120)),
        CapsuleEqPreset("vocal_v2", "Vocals",
            listOf(-65, -75, -50, 5, 80, 155, 195, 165, 35, -60)),
        CapsuleEqPreset("air_v2", "Air",
            listOf(-50, -65, -50, -25, 0, 35, 85, 130, 165, 130)),
        CapsuleEqPreset("live_v2", "Live",
            listOf(45, 95, 155, 130, 55, 50, 145, 155, 75, -30)),
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
