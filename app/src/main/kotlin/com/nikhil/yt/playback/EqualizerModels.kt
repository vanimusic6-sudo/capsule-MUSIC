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
internal const val CAPSULE_MAX_EQ_BOOST_MB = 900      // +9 dB; limiter remains active on Android 9+
// Sub-bass needs a much stricter ceiling: small phone speakers can over-excurse.
internal const val CAPSULE_MAX_LOW_BASS_BOOST_MB = 400 // +4 dB below 120 Hz
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
 * Moderate static headroom before the session effects.
 *
 * Do not add worst-case theoretical gains from independent effects: that
 * dropped some music by 17+ dB even when the output was clean. Reserve most
 * of the strongest likely overlapping boost and leave peak transients to the
 * optional DynamicsProcessing limiter on compatible devices.
 *
 * This is a loudness/quality tradeoff, NOT a guaranteed true-peak ceiling.
 * OEM effect gains/ordering and speaker excursion are not measurable here.
 * Low-bass boosts remain capped separately to reduce driver stress.
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
    if (eqBoostDb <= 0.0 && bassAllowanceDb <= 0.0 &&
        virtualizerAllowanceDb <= 0.0 && gainBoostDb <= 0.0
    ) return 1f

    // A fixed, conservative *moderate* reserve: avoid turning normal music
    // into a whisper just because several controls are enabled together.
    // These effects typically occupy different spectral regions; they must
    // not be treated as identical full-band +dB amplifiers.
    val mainReserveDb = maxOf(
        eqBoostDb * 0.33,
        bassAllowanceDb * 0.35,
    )
    val sharedLowEndDb = minOf(eqBoostDb, bassAllowanceDb) * 0.10
    // Avoid cancelling the tonal boost with excess global attenuation.
    // Still reserve additional headroom when explicit master gain is enabled.
    val reserveDb = (mainReserveDb + sharedLowEndDb +
        gainBoostDb * 0.25 + virtualizerAllowanceDb * 0.10).coerceIn(0.0, 4.0)
    return Math.pow(10.0, -reserveDb / 20.0).toFloat().coerceIn(0.63f, 1f)
}

/**
 * Preserve half the useful loudness-normalization boost while EQ is active.
 * The previous version discarded all positive normalization and made quiet
 * tracks disproportionately quiet. This is NOT a true-peak safety stage.
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
    // The base already includes normalization. Keep half its dB increase
    // rather than removing it entirely or granting its full +3 dB boost.
    val normalizationCompensation = if (eqEnabled) {
        kotlin.math.sqrt(normalizationFactor.coerceAtLeast(1f))
    } else 1f
    return (baseVolume.coerceAtLeast(0f) * safetyFactor.coerceIn(0f, 1f) *
        transitionFactor.coerceIn(0f, 1f) / normalizationCompensation)
        .coerceIn(0f, 1f)
}

internal object EqualizerJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

