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

/**
 * Conservative, always-on bounds for the phone's output stage.
 * These protect against excessive combined boost but cannot measure speaker
 * temperature, acoustic excursion, or guarantee physical speaker safety.
 */
internal const val CAPSULE_MAX_EQ_BOOST_MB = 650      // +6.5 dB per EQ band
internal const val CAPSULE_MAX_BASS_STRENGTH = 400    // native scale 0..1000
internal const val CAPSULE_MAX_VIRTUALIZER_STRENGTH = 350
internal const val CAPSULE_MAX_OUTPUT_GAIN_MB = 300   // +3 dB

/** Hardware-mapped effective EQ curve, or flat when the studio is bypassed. */
internal fun protectedEqLevels(
    settings: EqSettings,
    bandCount: Int,
    minMb: Int,
    maxMb: Int,
): List<Int> {
    if (bandCount <= 0) return emptyList()
    if (!settings.enabled) return List(bandCount) { 0 }
    val boundedMax = maxMb.coerceAtMost(CAPSULE_MAX_EQ_BOOST_MB).coerceAtLeast(minMb)
    return scaleEqBandLevels(
        resampleEqLevelsByIndex(settings.bandLevelsMb, bandCount),
        settings.intensityPercent,
        minMb,
        maxMb,
    ).map { it.coerceIn(minMb, boundedMax) }
}

/**
 * Reserve track headroom *before* the Android session effects. A track with
 * peaks near 0 dBFS can distort when independently boosted EQ/Bass/Gain bands
 * are summed. Never increase the track level from this policy.
 */
internal fun protectedEqAudioFactor(settings: EqSettings): Float {
    if (!settings.enabled) return 1f
    val maxPositiveEqMb = settings.bandLevelsMb
        .maxOfOrNull { it.coerceAtLeast(0) } ?: 0
    val eqBoostDb = (maxPositiveEqMb.toDouble() *
        settings.intensityPercent.coerceIn(100, 200) / 100.0 / 100.0)
        .coerceIn(0.0, CAPSULE_MAX_EQ_BOOST_MB / 100.0)
    val bassReserveDb = if (settings.bassBoostEnabled) {
        settings.bassBoostStrength.coerceIn(0, CAPSULE_MAX_BASS_STRENGTH) *
            (4.5 / CAPSULE_MAX_BASS_STRENGTH)
    } else 0.0
    val surroundReserveDb = if (settings.virtualizerEnabled) {
        settings.virtualizerStrength.coerceIn(0, CAPSULE_MAX_VIRTUALIZER_STRENGTH) *
            (1.5 / CAPSULE_MAX_VIRTUALIZER_STRENGTH)
    } else 0.0
    val gainReserveDb = if (settings.outputGainEnabled) {
        settings.outputGainMb.coerceIn(0, CAPSULE_MAX_OUTPUT_GAIN_MB) / 100.0
    } else 0.0
    val totalDb = eqBoostDb + bassReserveDb + surroundReserveDb +
        gainReserveDb + if (eqBoostDb + bassReserveDb + gainReserveDb > 0.0) 1.0 else 0.0
    return kotlin.math.pow(10.0, -totalDb / 20.0).toFloat().coerceIn(0.1f, 1f)
}

internal object EqualizerJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

