package com.nikhil.yt.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
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
        if (audioEffectsSessionId == sessionId && equalizer != null) return

        release()
        audioEffectsSessionId = sessionId

        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        loudnessEnhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()

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
        // Bass, spatializer and loudness can exist on devices whose native
        // Equalizer instance is unavailable. They must not be disabled by an
        // early return from an unrelated processor.
        equalizer?.let { eq ->
        val caps = capabilities.value
        val bandCount = caps?.bandCount ?: eq.numberOfBands.toInt()
        val minMb =
            caps?.minBandLevelMb
                ?: runCatching { eq.bandLevelRange.getOrNull(0)?.toInt() }.getOrNull()
                ?: -1500
        val maxMb =
            caps?.maxBandLevelMb
                ?: runCatching { eq.bandLevelRange.getOrNull(1)?.toInt() }.getOrNull()
                ?: 1500

        val levels = scaleEqBandLevels(
            resampleEqLevelsByIndex(settings.bandLevelsMb, bandCount),
            settings.intensityPercent,
            minMb,
            maxMb,
        )
        if (appliedEnabled != settings.enabled) {
            if (runCatching { eq.enabled = settings.enabled }.isSuccess) {
                appliedEnabled = settings.enabled
            }
        }
        if (appliedBandLevels.size != bandCount) {
            appliedBandLevels = IntArray(bandCount) { Int.MIN_VALUE }
        }
        for (band in 0 until bandCount) {
            val levelMb = levels.getOrNull(band)?.coerceIn(minMb, maxMb) ?: 0
            if (appliedBandLevels[band] != levelMb &&
                runCatching { eq.setBandLevel(band.toShort(), levelMb.toShort()) }.isSuccess
            ) {
                appliedBandLevels[band] = levelMb
            }
        }
        } // end native EQ configuration

        bassBoost?.let { effect ->
            val desiredStrength = settings.bassBoostStrength.coerceIn(0, 1000)
            if (settings.bassBoostEnabled && appliedBassStrength != desiredStrength) {
                if (runCatching {
                        if (effect.strengthSupported) effect.setStrength(desiredStrength.toShort())
                    }.isSuccess) appliedBassStrength = desiredStrength
            }
            if (appliedBassEnabled != settings.bassBoostEnabled) {
                if (runCatching { effect.enabled = settings.bassBoostEnabled }.isSuccess) {
                    appliedBassEnabled = settings.bassBoostEnabled
                }
            }
        }
        virtualizer?.let { effect ->
            val desiredStrength = settings.virtualizerStrength.coerceIn(0, 1000)
            if (settings.virtualizerEnabled && appliedVirtualizerStrength != desiredStrength) {
                if (runCatching {
                        if (effect.strengthSupported) effect.setStrength(desiredStrength.toShort())
                    }.isSuccess) appliedVirtualizerStrength = desiredStrength
            }
            if (appliedVirtualizerEnabled != settings.virtualizerEnabled) {
                if (runCatching { effect.enabled = settings.virtualizerEnabled }.isSuccess) {
                    appliedVirtualizerEnabled = settings.virtualizerEnabled
                }
            }
        }
        loudnessEnhancer?.let { effect ->
            val gainMb = settings.outputGainMb.coerceIn(-1500, 1500)
            if (settings.outputGainEnabled && appliedGainMb != gainMb) {
                if (runCatching { effect.setTargetGain(gainMb) }.isSuccess) {
                    appliedGainMb = gainMb
                }
            }
            if (appliedGainEnabled != settings.outputGainEnabled) {
                if (runCatching { effect.enabled = settings.outputGainEnabled }.isSuccess) {
                    appliedGainEnabled = settings.outputGainEnabled
                }
            }
        }
    }

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
