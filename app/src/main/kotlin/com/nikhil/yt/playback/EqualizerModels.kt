/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val bandCenterFreqHz: List<Int> = emptyList(),
    val bandLevelsMb: List<Int> = emptyList(),
    val outputGainMb: Int = 0,
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
)

@Serializable
data class EqProfilesPayload(
    @SerialName("profiles")
    val profiles: List<EqProfile> = emptyList(),
)

data class EqCapabilities(
    val bandCount: Int,
    val minBandLevelMb: Int,
    val maxBandLevelMb: Int,
    val centerFreqHz: List<Int>,
    val systemPresets: List<String>,
)

data class EqSettings(
    val enabled: Boolean,
    val bandLevelsMb: List<Int>,
    val outputGainEnabled: Boolean,
    val outputGainMb: Int,
    val bassBoostEnabled: Boolean,
    val bassBoostStrength: Int,
    val virtualizerEnabled: Boolean,
    val virtualizerStrength: Int,
    // 100% = original hardware curve. 200% expands the curve until the
    // device-reported band limits, without pretending to exceed native DSP.
    val intensityPercent: Int = 100,
)

/** Amplifies EQ *shape*, not master output volume; prevents out-of-range native calls. */
internal fun scaleEqBandLevels(
    levels: List<Int>,
    percent: Int,
    deviceMinMb: Int,
    deviceMaxMb: Int,
): List<Int> {
    if (deviceMaxMb < deviceMinMb) return levels
    val p = percent.coerceIn(100, 200)
    return levels.map { level ->
        ((level.toLong() * p + if (level >= 0) 50L else -50L) / 100L)
            .coerceIn(deviceMinMb.toLong(), deviceMaxMb.toLong()).toInt()
    }
}

internal fun inverseScaledEqLevel(
    effectiveMb: Int,
    percent: Int,
    deviceMinMb: Int,
    deviceMaxMb: Int,
): Int {
    val p = percent.coerceIn(100, 200)
    return ((effectiveMb.toLong() * 100L) / p.toLong())
        .coerceIn(deviceMinMb.toLong(), deviceMaxMb.toLong()).toInt()
}

internal object EqualizerJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

