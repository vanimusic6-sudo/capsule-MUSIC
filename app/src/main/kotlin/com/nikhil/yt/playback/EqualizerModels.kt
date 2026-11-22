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
// Extra protection for sub-bass: excursion is most demanding on small speakers.
internal const val CAPSULE_MAX_LOW_BASS_BOOST_MB = 300 // +3 dB below 120 Hz
internal const val CAPSULE_MAX_BASS_STRENGTH = 250     // native scale 0..1000
internal const val CAPSULE_MAX_VIRTUALIZER_STRENGTH = 350
internal const val CAPSULE_MAX_OUTPUT_GAIN_MB = 300   // +3 dB

/** Hardware-mapped effective EQ curve, or flat when the studio is bypassed. */
internal fun protectedEqLevels(
    settings: EqSettings,
    bandCount: Int,
    minMb: Int,
    maxMb: Int,
    centerFreqHz: List<Int> = emptyList(),
): List<Int> {
    if (bandCount <= 0) return emptyList()
    if (!settings.enabled || minMb > maxMb) return List(bandCount) { 0 }
    val boundedMax = maxMb.coerceAtMost(CAPSULE_MAX_EQ_BOOST_MB).coerceAtLeast(minMb)
    return scaleEqBandLevels(
        resampleEqLevelsByIndex(settings.bandLevelsMb, bandCount),
        settings.intensityPercent,
        minMb,
        maxMb,
    ).mapIndexed { index, level ->
        // If an OEM omits its frequency map, treat the first band as bass.
        // Restrict both positive shelving boost and the independent BassBoost.
        val center = centerFreqHz.getOrNull(index)
        val lowBass = if (center == null || center <= 0) index == 0
            else center <= 120
        val upper = if (lowBass) {
            boundedMax.coerceAtMost(CAPSULE_MAX_LOW_BASS_BOOST_MB)
        } else boundedMax
        level.coerceIn(minMb, upper.coerceAtLeast(minMb))
    }
}

/**
 * Conservative static headroom BEFORE Android's audio session effects.
 *
 * The former -4 dB ceiling could not cover a +6.5 dB EQ boost, let alone
 * BassBoost and LoudnessEnhancer together. Reserve their *combined* boost
 * (rather than a fraction of the largest individual boost).
 *
 * BassBoost and Virtualizer strengths do not specify a portable dB gain on
 * Android, so their allowance is conservative but not a mathematical bound.
 * A supported session limiter provides a second line of defense; neither
 * mechanism can measure speaker excursion, voice-coil heat or acoustic SPL.
 */
internal fun protectedEqAudioFactor(
    settings: EqSettings,
    capabilities: EqCapabilities? = null,
): Float {
    if (!settings.enabled) return 1f
    val levels = protectedEqLevels(
        settings = settings,
        bandCount = capabilities?.bandCount ?: settings.bandLevelsMb.size,
        minMb = capabilities?.minBandLevelMb ?: -1500,
        maxMb = capabilities?.maxBandLevelMb ?: CAPSULE_MAX_EQ_BOOST_MB,
        centerFreqHz = capabilities?.centerFreqHz.orEmpty(),
    )
    val eqBoostDb = if (capabilities == null) {
        // Until the OEM band map is known, assume even band zero can be a
        // mid/high band. Never use the sub-bass cap to UNDERestimate headroom.
        val requestedMaxMb = settings.bandLevelsMb.maxOfOrNull {
            it.coerceAtLeast(0)
        } ?: 0
        (requestedMaxMb.toDouble() * settings.intensityPercent.coerceIn(100, 200) /
            10_000.0).coerceIn(0.0, CAPSULE_MAX_EQ_BOOST_MB / 100.0)
    } else {
        (levels.maxOfOrNull { it.coerceAtLeast(0) } ?: 0) / 100.0
    }
    val bassAllowanceDb = if (settings.bassBoostEnabled) {
        settings.bassBoostStrength.coerceIn(0, CAPSULE_MAX_BASS_STRENGTH) *
            (5.0 / CAPSULE_MAX_BASS_STRENGTH)
    } else 0.0
    val virtualizerAllowanceDb = if (settings.virtualizerEnabled) {
        settings.virtualizerStrength.coerceIn(0, CAPSULE_MAX_VIRTUALIZER_STRENGTH) *
            (2.0 / CAPSULE_MAX_VIRTUALIZER_STRENGTH)
    } else 0.0
    val gainBoostDb = if (settings.outputGainEnabled) {
        settings.outputGainMb.coerceIn(0, CAPSULE_MAX_OUTPUT_GAIN_MB) / 100.0
    } else 0.0
    val combinedBoostDb = eqBoostDb + bassAllowanceDb +
        virtualizerAllowanceDb + gainBoostDb
    if (combinedBoostDb <= 0.0) return 1f

    // 1 dB additional margin for inter-sample peaks and filter interaction.
    // Never make the source louder; there is no floor that defeats protection.
    val reserveDb = combinedBoostDb + 1.0
    return Math.pow(10.0, -reserveDb / 20.0).toFloat().coerceIn(0f, 1f)
}

/**
 * Normalize the final player volume without giving normalization permission
 * to eat into the EQ headroom. This calculation is cheap and runs only when
 * the relevant flows change, not in the audio render loop.
 */
internal fun protectedEqPlaybackVolume(
    baseVolume: Float,
    normalizationFactor: Float,
    safetyFactor: Float,
    transitionFactor: Float,
    eqEnabled: Boolean,
): Float {
    if (!baseVolume.isFinite() || !normalizationFactor.isFinite() ||
        !safetyFactor.isFinite() || !transitionFactor.isFinite()
    ) return 0f
    val normalizationBoost = if (eqEnabled) normalizationFactor.coerceAtLeast(1f)
        else 1f
    return (baseVolume.coerceAtLeast(0f) * safetyFactor.coerceIn(0f, 1f) *
        transitionFactor.coerceIn(0f, 1f) / normalizationBoost)
        .coerceIn(0f, 1f)
}

internal object EqualizerJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

