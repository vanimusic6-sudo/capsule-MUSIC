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
    // The service and UI default this ON; false preserves explicit precision
    // and backward-compatible pure model fixtures.
    val musicalBalanceEnabled: Boolean = false,
    // Only identified fixed versions are allowed in the new live DSP path.
    val versionId: String? = null,
)

/**
 * Ten independent studio bands are configured with Android DynamicsProcessing
 * (API 28+). On an unsupported OEM the 10-point curve is interpolated onto
 * actual native Equalizer bands; that fallback is NOT a ten-band processor.
 */
internal val CAPSULE_STUDIO_FREQUENCIES_HZ =
    listOf(40, 80, 160, 315, 630, 1_250, 2_500, 5_000, 10_000, 16_000)

internal val CAPSULE_STUDIO_CAPABILITIES = EqCapabilities(
    CAPSULE_STUDIO_FREQUENCIES_HZ.size, -1500, 1500,
    CAPSULE_STUDIO_FREQUENCIES_HZ, emptyList(),
)

/** Map the saved 10-point contour by actual Hz, never by OEM band index. */
internal fun capsuleProjectStudioLevels(levels: List<Int>, targetHz: List<Int>): List<Int> {
    if (targetHz.isEmpty()) return emptyList()
    val studio = resampleEqLevelsByIndex(
        levels.map { it.coerceIn(-1500, 1500) }, CAPSULE_STUDIO_FREQUENCIES_HZ.size,
    )
    val valid = targetHz.all { it in 20..20_000 } &&
        targetHz.zipWithNext().all { (left, right) -> left < right }
    if (!valid) return resampleEqLevelsByIndex(studio, targetHz.size)
    val refs = CAPSULE_STUDIO_FREQUENCIES_HZ
    return targetHz.map { hz ->
        val i = refs.indexOfLast { it <= hz }.coerceIn(0, refs.lastIndex)
        when {
            i == refs.lastIndex -> studio.last()
            hz <= refs.first() -> studio.first()
            else -> {
                val x = kotlin.math.ln(hz.toDouble())
                val x0 = kotlin.math.ln(refs[i].toDouble())
                val x1 = kotlin.math.ln(refs[i + 1].toDouble())
                val t = ((x - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
                (studio[i] + (studio[i + 1] - studio[i]) * t).toInt()
            }
        }
    }
}

/** Amplifies EQ *shape*, not master output volume; prevents out-of-range native calls. */
internal fun scaleEqBandLevels(
    levels: List<Int>,
    percent: Int,
    deviceMinMb: Int,
    deviceMaxMb: Int,
): List<Int> {
    if (deviceMaxMb < deviceMinMb) return levels
    val p = percent.coerceIn(100, 145)
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
    val p = percent.coerceIn(100, 145)
    return ((effectiveMb.toLong() * 100L) / p.toLong())
        .coerceIn(deviceMinMb.toLong(), deviceMaxMb.toLong()).toInt()
}

/**
 * Conservative, always-on bounds for the phone's output stage.
 * These protect against excessive combined boost but cannot measure speaker
 * temperature, acoustic excursion, or guarantee physical speaker safety.
 */
internal const val CAPSULE_MAX_EQ_BOOST_MB = 320  // +3.2 dB, independent of master output volume
// Sub-bass needs a much stricter ceiling: small phone speakers can over-excurse.
internal const val CAPSULE_MAX_LOW_BASS_BOOST_MB = 220 // +2.2 dB below 120 Hz
internal const val CAPSULE_MAX_BASS_STRENGTH = 200     // native scale 0..1000
internal const val CAPSULE_MAX_VIRTUALIZER_STRENGTH = 350
internal const val CAPSULE_MAX_OUTPUT_GAIN_MB = 0 // No positive output makeup gain

/** Hardware-mapped effective EQ curve, or flat when the studio is bypassed. */
internal fun protectedEqLevels(
    settings: EqSettings,
    bandCount: Int,
    minMb: Int,
    maxMb: Int,
    centerFreqHz: List<Int> = emptyList(),
): List<Int> {
    if (bandCount <= 0) return emptyList()
    val n = bandCount.coerceAtMost(32)
    if (!settings.enabled || minMb > maxMb) return List(n) { 0 }
    val boundedMax = maxMb.coerceAtMost(CAPSULE_MAX_EQ_BOOST_MB).coerceAtLeast(minMb)
    val nativeLevels = scaleEqBandLevels(
        resampleEqLevelsByIndex(settings.bandLevelsMb, n),
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
    // The obsolete saved bypass flag must never disable the live contour guard.
    return capsuleMusicalEqLevels(nativeLevels, centerFreqHz)
        .map { it.coerceIn(minMb, maxMb) }
}

/**
 * Android's DynamicsProcessing runs its input gain BEFORE PreEQ. A positive
 * frequency curve without negative preamp can overload an internal stage
 * before the last limiter catches the transient.
 *
 * Sample-peak and Android OEM implementations do not provide guaranteed
 * true-peak output safety. Reserve an extra margin for filter overlap and
 * intersample overs; do not claim this protects speaker mechanics or hearing.
 */
internal const val CAPSULE_EQ_HEADROOM_MARGIN_MB = 160 // 1.6 dB plus overlapping-band allowance

/**
 * Per-session clean input trim, in millibels, before the actual EQ.
 * Strong EQ remains strong *relative* to unaffected frequencies; attempting
 * to restore every lost dB by input makeup would recreate the same clipping.
 */
internal fun capsuleSafeEqInputGainMb(
    settings: EqSettings, effectiveLevelsMb: List<Int>,
): Int {
    if (!settings.enabled) return 0
    val attenuation = if (settings.outputGainEnabled)
        settings.outputGainMb.coerceIn(-1500, 0) else 0
    val positive = effectiveLevelsMb.filter { it > 0 }.sortedDescending()
    val peak = positive.firstOrNull() ?: 0
    // Adjacent filters overlap. Reserve a small extra margin for two simultaneous
    // positive bands instead of pretending the highest band is the only gain.
    val overlap = ((positive.getOrNull(1) ?: 0) * 0.25f)
        .toInt().coerceIn(0, 80)
    val reserve = if (peak > 0) peak + overlap + CAPSULE_EQ_HEADROOM_MARGIN_MB else 0
    return (attenuation - reserve).coerceIn(-3000, 0)
}

/**
 * Fallback for devices without a functioning unified DynamicsProcessing.
 *
 * Playback volume precedes native EQ. Reserve the ACTUAL potential positive
 * bands instead of the old fixed -4 dB ceiling: a +9 dB curve with only -4 dB
 * reserve can saturate the internal device mixer before a final limiter.
 *
 * Native BassBoost and Virtualizer are separate on this legacy path and may
 * overlap, so allocate additional reserve where appropriate.
 */
internal fun protectedEqAudioFactor(
    settings: EqSettings, capabilities: EqCapabilities? = null,
): Float {
    if (!settings.enabled) return 1f
    val caps = capabilities ?: EqCapabilities(
        settings.bandLevelsMb.size, -1500, CAPSULE_MAX_EQ_BOOST_MB,
        emptyList(), emptyList(),
    )
    val levels = capsuleUnifiedEqLevels(settings, caps)
    val trim = capsuleSafeEqInputGainMb(settings, levels)
    return Math.pow(10.0, trim / 2000.0).toFloat().coerceIn(0f, 1f)
}

/**
 * Preserve half the useful loudness-normalization boost while EQ is active.
 * The previous version discarded all positive normalization and made quiet
 * tracks disproportionately quiet. This is NOT a true-peak safety stage.
 */
internal fun protectedEqPlaybackVolume(
    baseVolume: Float, normalizationFactor: Float, safetyFactor: Float,
    transitionFactor: Float, eqEnabled: Boolean,
): Float {
    if (!baseVolume.isFinite() || !normalizationFactor.isFinite() ||
        !safetyFactor.isFinite() || !transitionFactor.isFinite()) return 0f
    // Normalization already contributes to baseVolume. Do not divide twice.
    return (baseVolume.coerceAtLeast(0f) *
        safetyFactor.coerceIn(0f, 1f) * transitionFactor.coerceIn(0f, 1f))
        .coerceIn(0f, 1f)
}

/** Import/profile parsing is untrusted input: bound it before it reaches DSP state. */
internal fun decodeEqBandLevels(raw: String?): List<Int> {
    if (raw.isNullOrBlank() || raw.length > 8192) return emptyList()
    return runCatching { EqualizerJson.json.decodeFromString<List<Int>>(raw) }
        .getOrNull()?.take(64)?.map { it.coerceIn(-1500, 1500) } ?: emptyList()
}

internal object EqualizerJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

