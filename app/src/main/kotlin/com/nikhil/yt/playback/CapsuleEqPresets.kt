package com.nikhil.yt.playback

import kotlin.math.ln
import kotlin.math.roundToInt

/** Musical anchors are a UI abstraction, not fixed OEM EQ band centres. */
internal val CAPSULE_ADAPTIVE_REFERENCE_HZ = listOf(
    80, 180, 400, 1_000, 2_600, 6_500, 13_000,
)
internal const val CAPSULE_ADAPTIVE_MANUAL_MAX_MB = 300
internal const val CAPSULE_ADAPTIVE_MANUAL_MIN_MB = -300
internal const val CAPSULE_ADAPTIVE_MANUAL_VERSION = "manual:adaptive"

internal data class CapsuleEqPreset(
    val id: String,
    val name: String,
    val referenceLevelsMb: List<Int>,
)
internal object CapsuleEqPresets {
    val all = listOf(
        CapsuleEqPreset("soft_bass_v3", "Soft Bass", listOf(130, 150, 100, 20, -25, -20, -30)),
        CapsuleEqPreset("warm_v3", "Warm", listOf(50, 110, 125, 35, -40, -65, -80)),
        CapsuleEqPreset("vocal_v3", "Vocals", listOf(-30, -15, 45, 110, 95, 25, 0)),
        CapsuleEqPreset("air_v3", "Air", listOf(-30, -25, -10, 10, 75, 100, 100)),
        CapsuleEqPreset("live_v3", "Live", listOf(35, 75, 50, 0, 55, 50, 30)),
    )
    fun byId(id: String): CapsuleEqPreset? = all.firstOrNull { it.id == id }

    fun levelsFor(
        preset: CapsuleEqPreset,
        centerFreqHz: List<Int>,
        minMb: Int, maxMb: Int,
    ): List<Int> = capsuleAdaptiveLevelsFor(
        preset.referenceLevelsMb, centerFreqHz, minMb, maxMb,
    )
}

/** Interpolate broad musical controls logarithmically onto the OEM EQ bands. */
internal fun capsuleAdaptiveLevelsFor(
    referenceLevelsMb: List<Int>,
    deviceCentersHz: List<Int>,
    minMb: Int, maxMb: Int,
): List<Int> {
    if (deviceCentersHz.isEmpty()) return emptyList()
    if (minMb > maxMb || referenceLevelsMb.size != CAPSULE_ADAPTIVE_REFERENCE_HZ.size) {
        return List(deviceCentersHz.size.coerceAtMost(32)) { 0 }
    }
    val n = deviceCentersHz.size.coerceAtMost(32)
    return List(n) { i ->
        val hz = deviceCentersHz[i].takeIf { it in 20..20_000 }?.toDouble()
            ?: if (n == 1) 1_000.0 else {
                val position = i.toDouble() / (n - 1)
                80.0 * Math.pow(13_000.0 / 80.0, position)
            }
        val refs = CAPSULE_ADAPTIVE_REFERENCE_HZ
        val lower = refs.indexOfLast { it <= hz }.coerceIn(0, refs.lastIndex)
        val value = when {
            hz <= refs.first() -> referenceLevelsMb.first()
            lower == refs.lastIndex -> referenceLevelsMb.last()
            else -> {
                val f0 = ln(refs[lower].toDouble())
                val f1 = ln(refs[lower + 1].toDouble())
                val progress = ((ln(hz) - f0) / (f1 - f0)).coerceIn(0.0, 1.0)
                (referenceLevelsMb[lower] * (1.0 - progress) +
                    referenceLevelsMb[lower + 1] * progress).roundToInt()
            }
        }
        value.coerceIn(minMb, maxMb)
    }
}
