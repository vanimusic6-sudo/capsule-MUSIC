/*
 * Capsule MUSIC — a single ordered tone processor, GPL-3.0.
 * Signal: input trim -> pre-EQ -> Android sample-peak limiter.
 * No automatic multiband compression; no separate boost effect.
 * Digital headroom is not equivalent to acoustic hearing safety.
 */
package com.nikhil.yt.playback

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.sqrt

internal fun capsuleDspCutoffsHz(centersHz: List<Int>, count: Int): List<Float> {
    if (count <= 0) return emptyList()
    val n = count.coerceAtMost(32)
    val centers = List(n) { i ->
        val fallback = 60.0 * Math.pow(14000.0 / 60.0,
            i.toDouble() / (n - 1).coerceAtLeast(1))
        (centersHz.getOrNull(i)?.takeIf { it in 20..20000 }?.toDouble()
            ?: fallback).coerceIn(30.0, 18000.0)
    }
    var previous = 25.0
    return List(n) { i ->
        val proposed = if (i == n - 1) 20000.0 else sqrt(centers[i] * centers[i+1])
        val cutoff = max(previous + 1.0, proposed).coerceAtMost(
            20000.0 - (n - i - 1))
        previous = cutoff
        cutoff.toFloat()
    }
}

/** Bass is part of the EQ contour; never apply a second amplifier. */
internal fun capsuleUnifiedEqLevels(
    settings: EqSettings, capabilities: EqCapabilities,
): List<Int> {
    if (!settings.enabled) return List(capabilities.bandCount.coerceAtLeast(0)) { 0 }
    val preset = settings.versionId?.let(CapsuleEqPresets::byId)
    // Require an explicitly recognized version, map by device Hz (NOT band
    // index), then use the same protective contour as the former custom EQ.
    val raw = if (preset != null) {
        CapsuleEqPresets.levelsFor(
            preset,
            (0 until capabilities.bandCount).map { capabilities.centerFreqHz.getOrNull(it) ?: 0 },
            capabilities.minBandLevelMb, capabilities.maxBandLevelMb,
        )
    } else capsuleProjectStudioLevels(
        settings.bandLevelsMb,
        (0 until capabilities.bandCount).map { capabilities.centerFreqHz.getOrNull(it) ?: 0 },
    )
    val levels = protectedEqLevels(
        settings.copy(
            bandLevelsMb = raw, intensityPercent = settings.intensityPercent.coerceIn(100, 145),
            bassBoostEnabled = false, bassBoostStrength = 0,
            outputGainEnabled = false, outputGainMb = 0,
        ),
        capabilities.bandCount, capabilities.minBandLevelMb,
        capabilities.maxBandLevelMb, capabilities.centerFreqHz,
    )
    // The low-frequency flavour control is a small additional *shelf* inside
    // this same shape, never a second OEM BassBoost effect or compressor.
    // Apply it BEFORE the global notch/spread and headroom policy.
    return levels
}

/** Compression changes the envelope of the song, so this stage is retired. */
internal fun capsuleNeedsBassDynamics(
    settings: EqSettings, capabilities: EqCapabilities,
): Boolean = false

@RequiresApi(Build.VERSION_CODES.P)
internal class CapsuleUnifiedDynamicsProcessor(
    sessionId: Int, private val capabilities: EqCapabilities,
) {
    private val cutoffs = capsuleDspCutoffsHz(
        capabilities.centerFreqHz, capabilities.bandCount,
    )
    private val appliedLevels = IntArray(cutoffs.size) { Int.MIN_VALUE }
    private var appliedInputGain: Int? = null
    private val effect: DynamicsProcessing

    init {
        require(sessionId > 0 && cutoffs.isNotEmpty())
        val eq = DynamicsProcessing.Eq(true, true, cutoffs.size)
        cutoffs.forEachIndexed { band, cutoff ->
            eq.setBand(band, DynamicsProcessing.EqBand(true, cutoff, 0f))
        }
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, true, cutoffs.size, false, 0, false, 0, true,
        ).setPreEqAllChannelsTo(eq)
            .setLimiterAllChannelsTo(nativeLimiter())
            .build()
        val newEffect = DynamicsProcessing(0, sessionId, config)
        try {
            newEffect.enabled = true
            check(newEffect.enabled) { "Android rejected the tonal processor" }
            effect = newEffect
        } catch (failure: Exception) {
            newEffect.release()
            throw failure
        }
    }

    fun apply(settings: EqSettings) {
        if (!settings.enabled) {
            // Original really is bypass: a flat PreEQ with an enabled -2 dB
            // limiter still changes transients. Short volume ducking during
            // the transition is handled by MusicService.
            effect.enabled = false
            appliedLevels.fill(Int.MIN_VALUE)
            appliedInputGain = null
            return
        }
        val curve = capsuleUnifiedEqLevels(settings, capabilities)
        require(curve.size == cutoffs.size)
        val preamp = capsuleSafeEqInputGainMb(settings, curve)
        // Reduce input BEFORE increasing any EQ band; restore only after
        // the potentially boosted band has been reduced.
        if (appliedInputGain == null || preamp < appliedInputGain!!) {
            effect.setInputGainAllChannelsTo(preamp / 100f)
            appliedInputGain = preamp
        }
        curve.forEachIndexed { band, gain ->
            if (appliedLevels[band] != gain) {
                effect.setPreEqBandAllChannelsTo(
                    band, DynamicsProcessing.EqBand(true, cutoffs[band], gain / 100f),
                )
                appliedLevels[band] = gain
            }
        }
        if (appliedInputGain != preamp) {
            effect.setInputGainAllChannelsTo(preamp / 100f)
            appliedInputGain = preamp
        }
        // Stage new gains while disabled, then enable once the input reserve
        // and all bands are ready. On an active session, no per-frame toggles.
        if (!effect.enabled) {
            effect.enabled = true
            check(effect.enabled) { "Android did not enable the configured DSP" }
        }
    }

    fun release() = effect.release()

    private fun nativeLimiter() = DynamicsProcessing.Limiter(
        true, true, 0, 0.8f, 160f, 20f, -2f, 0f,
    )
}
