/*
 * Capsule MUSIC — Android 9+ unified, session-local dynamics path.
 * Original implementation for this GPL-3.0 project.
 *
 * This is a sample-peak limiter, NOT an oversampled true-peak limiter.
 * It cannot measure the temperature or physical excursion of speakers.
 */
package com.nikhil.yt.playback

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.sqrt

/** Keep DPE cutoff frequencies strictly increasing and within the audible band. */
internal fun capsuleDspCutoffsHz(centersHz: List<Int>, count: Int): List<Float> {
    if (count <= 0) return emptyList()
    val countSafe = count.coerceAtMost(32)
    val fallback = List(countSafe) { band ->
        60.0 * Math.pow(14000.0 / 60.0, band.toDouble() / (countSafe - 1).coerceAtLeast(1))
    }
    val centers = List(countSafe) { band ->
        (centersHz.getOrNull(band)?.takeIf { it > 0 }?.toDouble()
            ?: fallback[band]).coerceIn(30.0, 18000.0)
    }
    val result = mutableListOf<Float>()
    var previous = 25.0
    for (band in 0 until countSafe) {
        val proposed = if (band == countSafe - 1) 20000.0 else
            sqrt(centers[band] * centers[band + 1])
        val cutoff = max(previous + 1.0, proposed).coerceAtMost(
            20000.0 - (countSafe - band - 1)
        )
        result.add(cutoff.toFloat())
        previous = cutoff
    }
    return result
}

/** BassBoost becomes controlled low-band EQ boost, not a second audio effect. */
internal fun capsuleUnifiedEqLevels(
    settings: EqSettings,
    capabilities: EqCapabilities,
): List<Int> {
    val levels = protectedEqLevels(
        settings, capabilities.bandCount,
        capabilities.minBandLevelMb, capabilities.maxBandLevelMb,
        capabilities.centerFreqHz,
    )
    if (!settings.enabled || !settings.bassBoostEnabled) return levels
    val bassBoostMb = (settings.bassBoostStrength
        .coerceIn(0, CAPSULE_MAX_BASS_STRENGTH) * 250 /
        CAPSULE_MAX_BASS_STRENGTH)
    return levels.mapIndexed { index, original ->
        val freq = capabilities.centerFreqHz.getOrNull(index) ?: 0
        val isBass = if (freq <= 0) index == 0 else freq <= 120
        if (isBass) (original + bassBoostMb).coerceAtMost(350)
        else original
    }
}

internal fun capsuleNeedsBassDynamics(
    settings: EqSettings,
    capabilities: EqCapabilities,
): Boolean {
    if (!settings.enabled) return false
    if (settings.bassBoostEnabled && settings.bassBoostStrength > 0) return true
    return protectedEqLevels(
        settings, capabilities.bandCount, capabilities.minBandLevelMb,
        capabilities.maxBandLevelMb, capabilities.centerFreqHz,
    ).anyIndexed { index, level ->
        val hz = capabilities.centerFreqHz.getOrNull(index) ?: 0
        (if (hz <= 0) index == 0 else hz <= 120) && level > 100
    }
}

private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    for (index in indices) if (predicate(index, get(index))) return true
    return false
}

/**
 * One platform chain: PreEQ -> 2-band compressor -> limiter.
 * The native EQ is still used only for device band metadata and OS presets
 * in the controller; it must never be enabled concurrently with this chain.
 */
@RequiresApi(Build.VERSION_CODES.P)
internal class CapsuleUnifiedDynamicsProcessor(
    sessionId: Int,
    private val capabilities: EqCapabilities,
) {
    private val cutoffs = capsuleDspCutoffsHz(
        capabilities.centerFreqHz, capabilities.bandCount,
    )
    private val appliedLevels = IntArray(cutoffs.size) { Int.MIN_VALUE }
    private var appliedBassDynamics: Boolean? = null
    private var appliedInputGain: Int? = null
    // The initial platform graph is already limited before it processes a sample.
    private var appliedLimiter: Boolean? = true
    private val processor: DynamicsProcessing

    init {
        require(sessionId > 0)
        require(cutoffs.isNotEmpty())
        val eq = DynamicsProcessing.Eq(true, true, cutoffs.size)
        cutoffs.forEachIndexed { band, cutoff ->
            // Enabled=false on an EqBand can SILENCE its band, not bypass it.
            eq.setBand(band, DynamicsProcessing.EqBand(true, cutoff, 0f))
        }
        val mbc = DynamicsProcessing.Mbc(true, true, 2)
        mbc.setBand(0, nativeBassBand(false))
        mbc.setBand(1, nativeTrebleBand())
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, true, cutoffs.size, true, 2, false, 0, true,
        ).setPreEqAllChannelsTo(eq)
            .setMbcAllChannelsTo(mbc)
            .setLimiterAllChannelsTo(nativeLimiter(true))
            .build()
        val effect = DynamicsProcessing(0, sessionId, config)
        try {
            effect.enabled = true
            check(effect.enabled) { "Unified DynamicsProcessing did not activate" }
            processor = effect
        } catch (failure: Exception) {
            effect.release()
            throw failure
        }
    }

    fun apply(settings: EqSettings) {
        // Activate the final limiter BEFORE any EQ/input gain increases.
        // When bypassing, the curve/gain are neutralized BEFORE limiter removal.
        if (settings.enabled && appliedLimiter != true) {
            processor.setLimiterAllChannelsTo(nativeLimiter(true))
            appliedLimiter = true
        }
        val levels = capsuleUnifiedEqLevels(settings, capabilities)
        require(levels.size == cutoffs.size)
        for (band in levels.indices) {
            if (appliedLevels[band] == levels[band]) continue
            processor.setPreEqBandAllChannelsTo(
                band, DynamicsProcessing.EqBand(
                    true, cutoffs[band], levels[band] / 100f,
                ),
            )
            appliedLevels[band] = levels[band]
        }
        val bassDynamics = capsuleNeedsBassDynamics(settings, capabilities)
        if (appliedBassDynamics != bassDynamics) {
            processor.setMbcBandAllChannelsTo(0, nativeBassBand(bassDynamics))
            appliedBassDynamics = bassDynamics
        }
        val inputGain = if (settings.enabled && settings.outputGainEnabled)
            settings.outputGainMb.coerceIn(-1500, CAPSULE_MAX_OUTPUT_GAIN_MB) else 0
        if (appliedInputGain != inputGain) {
            processor.setInputGainAllChannelsTo(inputGain / 100f)
            appliedInputGain = inputGain
        }
        if (!settings.enabled && appliedLimiter != false) {
            processor.setLimiterAllChannelsTo(nativeLimiter(false))
            appliedLimiter = false
        }
    }

    fun release() = processor.release()

    private fun nativeBassBand(enabled: Boolean) = DynamicsProcessing.MbcBand(
        true, 160f, 8f, 190f,
        if (enabled) 2.5f else 1f,
        if (enabled) -9f else 0f,
        3f, -80f, 1f, 0f, 0f,
    )

    private fun nativeTrebleBand() = DynamicsProcessing.MbcBand(
        true, 20000f, 8f, 190f,
        1f, 0f, 0f, -80f, 1f, 0f, 0f,
    )

    private fun nativeLimiter(enabled: Boolean) = DynamicsProcessing.Limiter(
        true, true, 0, 1.5f, 140f,
        if (enabled) 20f else 1f,
        if (enabled) -1.5f else 0f, 0f,
    )
}
