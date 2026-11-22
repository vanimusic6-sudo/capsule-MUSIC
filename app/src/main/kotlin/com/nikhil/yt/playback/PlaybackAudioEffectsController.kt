package com.nikhil.yt.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow

/** Owns Android audio-effect instances for the active ExoPlayer audio session. */
internal class PlaybackAudioEffectsController(
    private val onRecoverableError: (operation: String, error: Throwable) -> Unit,
) {
    val capabilities = MutableStateFlow<EqCapabilities?>(null)

    private var desiredSettings = DEFAULT_SETTINGS
    private var audioEffectsSessionId: Int? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    // Optional session peak limiting on API 28+. Static headroom is always
    // enforced as well, including when this device lacks DynamicsProcessing.
    private var peakLimiter: DynamicsProcessing? = null
    private var attemptedPeakLimiter = false
    private var activeLimiterCurve: Boolean? = null
    // Avoid rewriting identical hardware parameters on unrelated preference
    // updates. Invalidated whenever the audio session or preset changes.
    private var appliedBandLevels: IntArray = intArrayOf()
    private var appliedEnabled: Boolean? = null
    // Native OEM effects can rebuild the output mixer on redundant parameter
    // writes. Keep desired and applied states separate and avoid touching an
    // OFF effect's strength when another preference changes.
    private var appliedBassEnabled: Boolean? = null
    private var appliedBassStrength: Int? = null
    private var appliedVirtualizerEnabled: Boolean? = null
    private var appliedVirtualizerStrength: Int? = null
    private var appliedGainEnabled: Boolean? = null
    private var appliedGainMb: Int? = null


    fun applySettings(settings: EqSettings) {
        desiredSettings = settings
        applySettingsToEffects(settings)
    }

    fun currentBandCount(): Int =
        capabilities.value?.bandCount
            ?: runCatching { equalizer?.numberOfBands?.toInt() }.getOrNull()
            ?: 0

    fun ensure(sessionId: Int) {
        if (sessionId <= 0) return
        if (audioEffectsSessionId == sessionId) return

        release()
        audioEffectsSessionId = sessionId

        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        loudnessEnhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()

        // Read the engine's initial state rather than blindly calling
        // setEnabled(false) on every new session. Some OEM mixers briefly
        // underrun even on a redundant OFF command.
        appliedEnabled = equalizer?.let { runCatching { it.enabled }.getOrNull() }
        appliedBassEnabled = bassBoost?.let { runCatching { it.enabled }.getOrNull() }
        appliedVirtualizerEnabled = virtualizer?.let {
            runCatching { it.enabled }.getOrNull()
        }
        appliedGainEnabled = loudnessEnhancer?.let { runCatching { it.enabled }.getOrNull() }

        equalizer?.let(::updateCapabilitiesFromEffect)
        applySettingsToEffects(desiredSettings)
    }

    fun applySystemPreset(
        sessionId: Int,
        presetIndex: Int,
    ): List<Int>? {
        ensure(sessionId)
        val eq = equalizer ?: return null
        val maxPreset = runCatching { eq.numberOfPresets.toInt() }.getOrNull() ?: 0
        if (presetIndex !in 0 until maxPreset) return null
        runCatching { eq.usePreset(presetIndex.toShort()) }.getOrNull() ?: return null
        // Android changed the hardware levels directly; cached values are stale.
        appliedBandLevels = intArrayOf()

        val bandCount = runCatching { eq.numberOfBands.toInt() }.getOrNull() ?: 0
        return (0 until bandCount).map { band ->
            runCatching { eq.getBandLevel(band.toShort()).toInt() }.getOrNull() ?: 0
        }
    }

    fun release() {
        audioEffectsSessionId = null
        releaseEffect("release equalizer") { equalizer?.release() }
        releaseEffect("release bass boost") { bassBoost?.release() }
        releaseEffect("release virtualizer") { virtualizer?.release() }
        releaseEffect("release loudness enhancer") { loudnessEnhancer?.release() }
        releaseEffect("release peak limiter") { peakLimiter?.release() }
        equalizer = null
        appliedBandLevels = intArrayOf()
        appliedEnabled = null
        appliedBassEnabled = null
        appliedBassStrength = null
        appliedVirtualizerEnabled = null
        appliedVirtualizerStrength = null
        appliedGainEnabled = null
        appliedGainMb = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        peakLimiter = null
        attemptedPeakLimiter = false
        activeLimiterCurve = null
        // Retain the last native frequency map while paused. No effects remain
        // attached, but the UI can edit a profile for the next valid session.
    }

    private fun releaseEffect(
        operation: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (error: Exception) {
            onRecoverableError(operation, error)
        }
    }

    private fun updateCapabilitiesFromEffect(eq: Equalizer) {
        val bandCount = eq.numberOfBands.toInt().coerceAtLeast(0)
        val range = runCatching { eq.bandLevelRange }.getOrNull()
        val minMb = range?.getOrNull(0)?.toInt() ?: -1500
        val maxMb = range?.getOrNull(1)?.toInt() ?: 1500
        val center =
            (0 until bandCount).map { band ->
                (runCatching { eq.getCenterFreq(band.toShort()) }.getOrNull() ?: 0) / 1000
            }
        val presets =
            (0 until eq.numberOfPresets.toInt()).map { index ->
                runCatching { eq.getPresetName(index.toShort()).toString() }.getOrNull()
                    ?: "Preset ${index + 1}"
            }

        capabilities.value =
            EqCapabilities(
                bandCount = bandCount,
                minBandLevelMb = minMb,
                maxBandLevelMb = maxMb,
                centerFreqHz = center,
                systemPresets = presets,
            )
    }

    private fun applySettingsToEffects(settings: EqSettings) {
        // A native enabled=false/true transition can rebuild the OEM output
        // mixer and glitch playback. Once a processor is enabled, bypass it by
        // sending a *neutral curve/strength/gain* instead of disconnecting it.
        // Effect instances are actually released when the audio session ends.
        equalizer?.let { eq ->
            val caps = capabilities.value
            val bandCount = caps?.bandCount ?: runCatching {
                eq.numberOfBands.toInt()
            }.getOrDefault(0)
            val minMb = caps?.minBandLevelMb ?: -1500
            val maxMb = caps?.maxBandLevelMb ?: 1500
            val levels = protectedEqLevels(
                settings, bandCount, minMb, maxMb, caps?.centerFreqHz.orEmpty(),
            )
            val nonFlat = levels.any { it != 0 }
            // Never activate a new effect for an inactive/flat profile.
            // When bypassing an already active EQ, keep it attached and make
            // its hardware bands flat instead of calling setEnabled(false).
            if (nonFlat && appliedEnabled != true) {
                if (runCatching { eq.enabled = true }.isSuccess) {
                    appliedEnabled = true
                }
            }
            if (appliedEnabled == true && bandCount > 0) {
                if (appliedBandLevels.size != bandCount) {
                    appliedBandLevels = IntArray(bandCount) { Int.MIN_VALUE }
                }
                for (band in 0 until bandCount) {
                    val mb = levels[band].coerceIn(minMb, maxMb)
                    if (appliedBandLevels[band] != mb &&
                        runCatching { eq.setBandLevel(band.toShort(), mb.toShort()) }.isSuccess
                    ) {
                        appliedBandLevels[band] = mb
                    }
                }
            }
        }

        bassBoost?.let { effect ->
            val target = if (settings.enabled && settings.bassBoostEnabled) {
                settings.bassBoostStrength.coerceIn(0, CAPSULE_MAX_BASS_STRENGTH)
            } else 0
            val supported = runCatching { effect.strengthSupported }.getOrDefault(false)
            if (target > 0 && supported && appliedBassEnabled != true) {
                if (supported && appliedBassStrength != target &&
                    runCatching { effect.setStrength(target.toShort()) }.isSuccess
                ) appliedBassStrength = target
                if (runCatching { effect.enabled = true }.isSuccess) {
                    appliedBassEnabled = true
                }
            }
            if (appliedBassEnabled == true && supported && appliedBassStrength != target) {
                if (runCatching { effect.setStrength(target.toShort()) }.isSuccess) {
                    appliedBassStrength = target
                }
            }
            // An unsupported-strength OEM processor cannot be neutralized.
            // Disable it only as the fallback (the service ducks audio then).
            if (!supported && appliedBassEnabled == true) {
                if (runCatching { effect.enabled = false }.isSuccess) {
                    appliedBassEnabled = false
                }
            }
        }

        virtualizer?.let { effect ->
            val target = if (settings.enabled && settings.virtualizerEnabled) {
                settings.virtualizerStrength.coerceIn(0, CAPSULE_MAX_VIRTUALIZER_STRENGTH)
            } else 0
            val supported = runCatching { effect.strengthSupported }.getOrDefault(false)
            if (target > 0 && supported && appliedVirtualizerEnabled != true) {
                if (supported && appliedVirtualizerStrength != target &&
                    runCatching { effect.setStrength(target.toShort()) }.isSuccess
                ) appliedVirtualizerStrength = target
                if (runCatching { effect.enabled = true }.isSuccess) {
                    appliedVirtualizerEnabled = true
                }
            }
            if (appliedVirtualizerEnabled == true && supported &&
                appliedVirtualizerStrength != target
            ) {
                if (runCatching { effect.setStrength(target.toShort()) }.isSuccess) {
                    appliedVirtualizerStrength = target
                }
            }
            if (!supported && appliedVirtualizerEnabled == true) {
                if (runCatching { effect.enabled = false }.isSuccess) {
                    appliedVirtualizerEnabled = false
                }
            }
        }

        loudnessEnhancer?.let { effect ->
            val targetMb = if (settings.enabled && settings.outputGainEnabled) {
                settings.outputGainMb.coerceIn(-1500, CAPSULE_MAX_OUTPUT_GAIN_MB)
            } else 0
            if (targetMb != 0 && appliedGainEnabled != true) {
                if (runCatching { effect.enabled = true }.isSuccess) {
                    appliedGainEnabled = true
                }
            }
            if (appliedGainEnabled == true && appliedGainMb != targetMb) {
                if (runCatching { effect.setTargetGain(targetMb) }.isSuccess) {
                    appliedGainMb = targetMb
                }
            }
        }
        updatePeakLimiter(settings)
    }

    /**
     * A post-processing limiter is useful on devices that implement the
     * Android DynamicsProcessing effect. Its ordering relative to OTHER OEM
     * effects is not guaranteed, so it supplements rather than replaces the
     * static attenuation above. Created once per session, never per slider.
     */
    private fun updatePeakLimiter(settings: EqSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val needsLimiter = settings.enabled && (
            settings.bandLevelsMb.any { it > 0 } ||
                (settings.bassBoostEnabled && settings.bassBoostStrength > 0) ||
                (settings.virtualizerEnabled && settings.virtualizerStrength > 0) ||
                (settings.outputGainEnabled && settings.outputGainMb > 0)
            )
        if (needsLimiter && !attemptedPeakLimiter) {
            attemptedPeakLimiter = true
            val sessionId = audioEffectsSessionId ?: return
            try {
                val limiter = nativeLimiter(active = true)
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                    2, false, 0, false, 0, false, 0, true,
                ).setLimiterAllChannelsTo(limiter).build()
                val effect = DynamicsProcessing(0, sessionId, config)
                try {
                    effect.enabled = true
                    if (!effect.enabled) error("Peak limiter did not activate")
                    peakLimiter = effect
                    activeLimiterCurve = true
                } catch (failure: Exception) {
                    effect.release()
                    throw failure
                }
            } catch (failure: Exception) {
                onRecoverableError("configure optional peak limiter", failure)
            }
        }
        val effect = peakLimiter ?: return
        if (activeLimiterCurve == needsLimiter) return
        try {
            // Ratio 1:1 turns the limiter neutral when the EQ is bypassed.
            // Keep its insert attached to avoid native mixer disconnect pops.
            effect.setLimiterAllChannelsTo(nativeLimiter(needsLimiter))
            activeLimiterCurve = needsLimiter
        } catch (failure: Exception) {
            onRecoverableError("update peak limiter", failure)
        }
    }

    private fun nativeLimiter(active: Boolean): DynamicsProcessing.Limiter =
        DynamicsProcessing.Limiter(
            true, true, 0, 0.5f, 120f,
            if (active) 20f else 1f,
            if (active) -1.0f else 0f,
            0f,
        )

    companion object {
        private val DEFAULT_SETTINGS =
            EqSettings(
                enabled = false,
                bandLevelsMb = emptyList(),
                outputGainEnabled = false,
                outputGainMb = 0,
                bassBoostEnabled = false,
                bassBoostStrength = 0,
                virtualizerEnabled = false,
                virtualizerStrength = 0,
            )
    }
}

internal fun resampleEqLevelsByIndex(
    levelsMb: List<Int>,
    targetCount: Int,
): List<Int> {
    if (targetCount <= 0) return emptyList()
    if (levelsMb.isEmpty()) return List(targetCount) { 0 }
    if (levelsMb.size == targetCount) return levelsMb
    if (targetCount == 1) return listOf(levelsMb.sum() / levelsMb.size)

    val lastIndex = levelsMb.lastIndex.toFloat().coerceAtLeast(1f)
    return List(targetCount) { index ->
        val position = index.toFloat() * lastIndex / (targetCount - 1).toFloat()
        val lower = kotlin.math.floor(position).toInt().coerceIn(0, levelsMb.lastIndex)
        val upper = kotlin.math.ceil(position).toInt().coerceIn(0, levelsMb.lastIndex)
        val fraction = (position - lower.toFloat()).coerceIn(0f, 1f)
        val start = levelsMb[lower]
        val end = levelsMb[upper]
        (start + ((end - start) * fraction)).toInt()
    }
}
