package com.nikhil.yt.playback

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Six recognizable sound signatures, expressed as millibels at fixed reference
 * frequencies. Native Android Equalizer hardware remains the only DSP. These
 * values never alter global system audio or turn on BassBoost/Virtualizer.
 */
internal data class CapsuleEqPreset(
    val id: String,
    val name: String,
    val referenceLevelsMb: List<Int>,
)

internal object CapsuleEqPresets {
    private val referenceHz = listOf(60, 230, 910, 3_600, 14_000)

    val all = listOf(
        // Each preset is a distinct *balanced* signature. Former presets had
        // +14 dB bass and +9 dB presence, saturating the graph and producing
        // needless pressure/harshness before any manual user adjustments.
        CapsuleEqPreset("deep_bass", "Deep Bass", listOf(380, 450, 40, -150, -100)),
        CapsuleEqPreset("punch", "Punch", listOf(270, 480, -90, 220, 100)),
        CapsuleEqPreset("vocal", "Vocal", listOf(-140, -90, 430, 290, 140)),
        CapsuleEqPreset("clarity", "Crystal", listOf(-200, -20, 150, 430, 250)),
        CapsuleEqPreset("warm", "Warm", listOf(270, 320, 120, -130, -170)),
        CapsuleEqPreset("v_shape", "V-Shape", listOf(320, 120, -300, 250, 320)),
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
            val hz = frequency.takeIf { it > 0 }?.toDouble()
                ?: if (last == 0) 910.0 else {
                    val position = index.toDouble() / last
                    60.0 * (14_000.0 / 60.0).pow(position)
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
