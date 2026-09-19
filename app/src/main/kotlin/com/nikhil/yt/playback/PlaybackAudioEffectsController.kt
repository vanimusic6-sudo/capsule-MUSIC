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
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        capabilities.value = null
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
        val eq = equalizer ?: return
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

        val levels = resampleEqLevelsByIndex(settings.bandLevelsMb, bandCount)
        runCatching { eq.enabled = settings.enabled }
        for (band in 0 until bandCount) {
            val levelMb = levels.getOrNull(band)?.coerceIn(minMb, maxMb) ?: 0
            runCatching { eq.setBandLevel(band.toShort(), levelMb.toShort()) }
        }

        bassBoost?.let { effect ->
            runCatching { effect.enabled = settings.bassBoostEnabled }
            runCatching { effect.setStrength(settings.bassBoostStrength.toShort()) }
        }
        virtualizer?.let { effect ->
            runCatching { effect.enabled = settings.virtualizerEnabled }
            runCatching { effect.setStrength(settings.virtualizerStrength.toShort()) }
        }
        loudnessEnhancer?.let { effect ->
            val gainMb =
                if (settings.outputGainEnabled) {
                    settings.outputGainMb.coerceIn(-1500, 1500)
                } else {
                    0
                }
            runCatching { effect.setTargetGain(gainMb) }
            runCatching { effect.enabled = settings.outputGainEnabled }
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
