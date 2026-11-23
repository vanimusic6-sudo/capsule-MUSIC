package com.nikhil.yt.playback

import kotlin.math.ln
import kotlin.math.roundToInt

internal data class CapsuleEqPreset(
    val id: String,
    val name: String,
    val referenceLevelsMb: List<Int>,
)

/**
 * Human-friendly tonal points, independent of the actual native Android
 * center frequencies. The OEM band centers remain where Android reports them.
 */
internal object CapsuleEqPresets {
    val referenceHz = listOf(80, 180, 450, 1_500, 4_500, 10_000)
    const val CUSTOM_ID = "custom_smart"
    private const val MAX_USER_MB = 350

    // Musical taste, not medically safe frequency bands. 100 mB = 1 dB.
    val all = listOf(
        CapsuleEqPreset("soft_bass_v2", "Soft Bass", listOf(70, 120, 60, 10, -10, -20)),
        CapsuleEqPreset("warm_v2", "Warm", listOf(40, 80, 75, 25, -45, -50)),
        CapsuleEqPreset("vocal_v2", "Vocals", listOf(-40, -10, 40, 110, 55, 0)),
        CapsuleEqPreset("air_v2", "Air", listOf(-20, -25, 0, 35, 85, 100)),
        CapsuleEqPreset("live_v2", "Live", listOf(20, 50, 60, 60, 30, 35)),
    )

    fun byId(id: String): CapsuleEqPreset? = all.firstOrNull { it.id == id }

    fun levelsFor(
        preset: CapsuleEqPreset, centerFreqHz: List<Int>,
        minMb: Int, maxMb: Int,
    ): List<Int> = mapReferenceLevels(
        preset.referenceLevelsMb, centerFreqHz, minMb, maxMb)

    /** Map six virtual controls into real OEM bands in logarithmic frequency. */
    fun mapReferenceLevels(
        controls: List<Int>, centers: List<Int>,
        minMb: Int, maxMb: Int,
    ): List<Int> {
        if (centers.isEmpty()) return emptyList()
        if (controls.size != referenceHz.size || minMb > maxMb)
            return List(centers.size) { 0 }
        val limited = controls.map { it.coerceIn(-MAX_USER_MB, MAX_USER_MB) }
        val lastIndex = centers.lastIndex
        return centers.mapIndexed { index, freq ->
            val hz = freq.takeIf { it in 20..20_000 }?.toDouble() ?: if (lastIndex==0)
                1_500.0 else 80.0*Math.pow(10_000.0/80.0, index.toDouble()/lastIndex)
            val left = referenceHz.indexOfLast { it <= hz }
                .coerceIn(0, referenceHz.lastIndex)
            val value = when {
                left == referenceHz.lastIndex -> limited.last()
                hz <= referenceHz.first() -> limited.first()
                else -> {
                    val a=ln(referenceHz[left].toDouble())
                    val b=ln(referenceHz[left+1].toDouble())
                    val t=((ln(hz)-a)/(b-a)).coerceIn(0.0,1.0)
                    (limited[left]+(limited[left+1]-limited[left])*t).roundToInt()
                }
            }
            value.coerceIn(minMb,maxMb)
        }
    }
}
