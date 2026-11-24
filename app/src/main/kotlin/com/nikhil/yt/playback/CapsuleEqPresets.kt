package com.nikhil.yt.playback

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Five smooth musical signatures on the independent ten-band DSP.
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
    private val referenceHz = CAPSULE_TEN_BAND_HZ

    // Broad shelves, without a midrange vacuum or narrow treble spikes.
    // +2.9 dB at the soft-bass peak is an intentionally pre-headroom bound,
    // not a guaranteed acoustic level. Digital guards still run after mapping.
    val all = listOf(
        CapsuleEqPreset("soft_bass_v2", "Soft Bass",
            listOf(180, 260, 290, 200, 100, 20, -70, -110, -100, -110)),
        CapsuleEqPreset("warm_v2", "Warm",
            listOf(120, 220, 235, 175, 90, -20, -90, -110, -120, -130)),
        CapsuleEqPreset("vocal_v2", "Vocals",
            listOf(-90, -80, -20, 70, 170, 220, 190, 95, -20, -80)),
        CapsuleEqPreset("air_v2", "Air",
            listOf(-75, -80, -50, -20, 20, 90, 160, 220, 250, 200)),
        CapsuleEqPreset("live_v2", "Live",
            listOf(80, 130, 140, 90, 100, 170, 200, 165, 80, -30)),
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
                ?: if (last == 0) 1_250.0 else {
                    val position = index.toDouble() / last
                    80.0 * (16_000.0 / 80.0).pow(position)
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
