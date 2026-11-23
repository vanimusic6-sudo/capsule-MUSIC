package com.nikhil.yt.playback

import android.media.audiofx.Equalizer
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class CapsuleEqEngineMode {
    OFF, WAITING_FOR_SESSION, UNIFIED, NATIVE_FALLBACK, UNAVAILABLE,
}

/** One audio session owns at most one active EQ graph. No independent boost effects. */
internal class PlaybackAudioEffectsController(
    private val onRecoverableError: (operation: String, error: Throwable) -> Unit,
) {
    val capabilities = MutableStateFlow<EqCapabilities?>(null)
    val engineMode = MutableStateFlow(CapsuleEqEngineMode.OFF)
    private var desiredSettings = defaultSettings
    private var sessionId: Int? = null
    private var equalizer: Equalizer? = null
    private var unifiedDynamics: CapsuleUnifiedDynamicsProcessor? = null
    private var attemptedUnifiedDynamics = false
    private var appliedBandLevels = intArrayOf()
    private var appliedEnabled: Boolean? = null

    val isUnifiedDynamicsActive: Boolean get() = unifiedDynamics != null
    fun currentBandCount(): Int = capabilities.value?.bandCount
        ?: runCatching { equalizer?.numberOfBands?.toInt() }.getOrNull() ?: 0

    fun applySettings(settings: EqSettings) {
        desiredSettings = settings
        applySettingsToEffects(settings)
    }

    fun ensure(sessionId: Int) {
        if (sessionId <= 0 || this.sessionId == sessionId) return
        release()
        this.sessionId = sessionId
        equalizer = runCatching { Equalizer(0, sessionId) }
            .onFailure { onRecoverableError("create native EQ", it) }.getOrNull()
        appliedEnabled = equalizer?.let { runCatching { it.enabled }.getOrNull() }
        equalizer?.let(::updateCapabilitiesFromEffect)
        applySettingsToEffects(desiredSettings)
    }

    /** Native preset APIs mutate DSP immediately, before safe headroom is staged. */
    fun applySystemPreset(sessionId: Int, presetIndex: Int): List<Int>? = null

    fun release() {
        sessionId = null
        // DynamicsProcessing exists only on API 28+; release must be guarded
        // too, even when the nullable engine was never created on API 26-27.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { unifiedDynamics?.release() }
                .onFailure { onRecoverableError("release unified EQ", it) }
        }
        runCatching { equalizer?.release() }
            .onFailure { onRecoverableError("release native EQ", it) }
        unifiedDynamics = null
        equalizer = null
        appliedEnabled = null
        appliedBandLevels = intArrayOf()
        attemptedUnifiedDynamics = false
        engineMode.value = if (desiredSettings.enabled)
            CapsuleEqEngineMode.WAITING_FOR_SESSION else CapsuleEqEngineMode.OFF
    }

    private fun updateCapabilitiesFromEffect(eq: Equalizer) {
        val n = runCatching { eq.numberOfBands.toInt() }.getOrDefault(0).coerceIn(0, 32)
        val range = runCatching { eq.bandLevelRange }.getOrNull()
        val low = range?.getOrNull(0)?.toInt() ?: -1500
        val high = range?.getOrNull(1)?.toInt() ?: 1500
        if (n <= 0 || low > high || 0 !in low..high) {
            capabilities.value = null
            onRecoverableError("invalid native EQ metadata", IllegalStateException(
                "Invalid native EQ frequency / gain range"))
            return
        }
        val center = (0 until n).map { band ->
            runCatching { eq.getCenterFreq(band.toShort()) }.getOrDefault(0) / 1000
        }
        val presetsCount = runCatching { eq.numberOfPresets.toInt() }
            .getOrDefault(0).coerceIn(0, 32)
        val presets = (0 until presetsCount).map { index ->
            runCatching { eq.getPresetName(index.toShort()).toString() }
                .getOrNull() ?: "Preset ${index + 1}"
        }
        capabilities.value = EqCapabilities(n, low, high, center, presets)
    }

    private fun applySettingsToEffects(settings: EqSettings) {
        val caps = capabilities.value
        val session = sessionId
        if (caps == null || session == null || equalizer == null) {
            engineMode.value = if (!settings.enabled) CapsuleEqEngineMode.OFF
                else if (session == null) CapsuleEqEngineMode.WAITING_FOR_SESSION
                else CapsuleEqEngineMode.UNAVAILABLE
            return
        }

        // "Original" must actually bypass the DSP. Keeping the DPE limiter
        // attached at -2 dB even with flat EQ changes the untreated signal.
        // MusicService performs a short attenuation ramp before this transition.
        if (!settings.enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { unifiedDynamics?.release() }
                    .onFailure { onRecoverableError("bypass unified EQ", it) }
            }
            unifiedDynamics = null
            attemptedUnifiedDynamics = false
            val native = equalizer
            if (appliedEnabled == true && native != null) {
                runCatching { native.enabled = false }
                    .onSuccess {
                        appliedEnabled = false
                        appliedBandLevels = intArrayOf()
                    }
                    .onFailure { onRecoverableError("bypass native EQ", it) }
            }
            engineMode.value = CapsuleEqEngineMode.OFF
            return
        }

        if (settings.enabled && unifiedDynamics == null && !attemptedUnifiedDynamics &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && appliedEnabled != true
        ) {
            attemptedUnifiedDynamics = true
            var candidate: CapsuleUnifiedDynamicsProcessor? = null
            try {
                candidate = CapsuleUnifiedDynamicsProcessor(session, caps)
                candidate.apply(settings)
                unifiedDynamics = candidate
            } catch (failure: Exception) {
                onRecoverableError("create unified tonal DSP", failure)
                runCatching { candidate?.release() }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            unifiedDynamics?.let { engine ->
                try {
                    engine.apply(settings)
                    engineMode.value = if (settings.enabled)
                        CapsuleEqEngineMode.UNIFIED else CapsuleEqEngineMode.OFF
                    return
                } catch (failure: Exception) {
                    onRecoverableError("update unified tonal DSP", failure)
                    runCatching { engine.release() }
                    unifiedDynamics = null
                }
            }
        }

        val eq = equalizer ?: return
        val levels = capsuleUnifiedEqLevels(settings, caps)
        val nonFlat = settings.enabled && levels.any { it != 0 }
        if (nonFlat && appliedEnabled != true &&
            runCatching { eq.enabled = true }.isSuccess
        ) appliedEnabled = true
        // Never disconnect an active native effect on a toggle: write flat 0 dB
        // instead. Avoid repeating writes that cause OEM mixer underruns.
        if (appliedEnabled == true) {
            if (appliedBandLevels.size != caps.bandCount)
                appliedBandLevels = IntArray(caps.bandCount) { Int.MIN_VALUE }
            for (band in 0 until caps.bandCount) {
                val gain = levels[band].coerceIn(caps.minBandLevelMb, caps.maxBandLevelMb)
                if (appliedBandLevels[band] != gain &&
                    runCatching { eq.setBandLevel(band.toShort(), gain.toShort()) }.isSuccess
                ) appliedBandLevels[band] = gain
            }
        }
        engineMode.value = when {
            !settings.enabled -> CapsuleEqEngineMode.OFF
            nonFlat && appliedEnabled != true -> CapsuleEqEngineMode.UNAVAILABLE
            else -> CapsuleEqEngineMode.NATIVE_FALLBACK
        }
    }

    companion object {
        private val defaultSettings = EqSettings(
            enabled = false, bandLevelsMb = emptyList(),
            outputGainEnabled = false, outputGainMb = 0,
            bassBoostEnabled = false, bassBoostStrength = 0,
            virtualizerEnabled = false, virtualizerStrength = 0,
        )
    }
}

internal fun resampleEqLevelsByIndex(
    levelsMb: List<Int>,
    targetCount: Int,
): List<Int> {
    if (targetCount <= 0) return emptyList()
    if (levelsMb.isEmpty()) return List(targetCount.coerceAtMost(32)) { 0 }
    if (levelsMb.size == targetCount && targetCount <= 32) return levelsMb
    if (targetCount == 1) return listOf((levelsMb.sumOf { it.toLong() } / levelsMb.size).toInt())

    val lastIndex = levelsMb.lastIndex.toFloat().coerceAtLeast(1f)
    return List(targetCount.coerceAtMost(32)) { index ->
        val position = index.toFloat() * lastIndex / (targetCount.coerceAtMost(32) - 1).toFloat()
        val lower = kotlin.math.floor(position).toInt().coerceIn(0, levelsMb.lastIndex)
        val upper = kotlin.math.ceil(position).toInt().coerceIn(0, levelsMb.lastIndex)
        val fraction = (position - lower.toFloat()).coerceIn(0f, 1f)
        val start = levelsMb[lower]
        val end = levelsMb[upper]
        (start.toDouble() + (end.toDouble() - start.toDouble()) * fraction).toInt()
    }
}
