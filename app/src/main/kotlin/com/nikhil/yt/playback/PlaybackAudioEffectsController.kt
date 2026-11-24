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
    private var nativeCapabilities: EqCapabilities? = null
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
        // Never stack Android Equalizer with DynamicsProcessing on one session.
        if (appliedEnabled == true) {
            equalizer?.let { native ->
                runCatching { native.enabled = false }
                    .onSuccess { appliedEnabled = false }
                    .onFailure { onRecoverableError("disable native before DSP", it) }
            }
        }
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
        nativeCapabilities = null
        capabilities.value = null
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
            nativeCapabilities = null
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
        nativeCapabilities = EqCapabilities(n, low, high, center, presets)
        capabilities.value = nativeCapabilities
    }

    private fun applySettingsToEffects(settings: EqSettings) {
        val session = sessionId
        if (session == null) {
            engineMode.value = if (!settings.enabled) CapsuleEqEngineMode.OFF
                else CapsuleEqEngineMode.WAITING_FOR_SESSION
            return
        }

        // Android 9+ can host a software-configured TEN-band DSP regardless
        // of how many immutable bands the OEM native Equalizer reports.
        if (settings.enabled && unifiedDynamics == null && !attemptedUnifiedDynamics &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && appliedEnabled != true
        ) {
            attemptedUnifiedDynamics = true
            var candidate: CapsuleUnifiedDynamicsProcessor? = null
            try {
                candidate = CapsuleUnifiedDynamicsProcessor(
                    session, capsuleTenBandCapabilities(),
                )
                candidate.apply(settings)
                unifiedDynamics = candidate
            } catch (failure: Exception) {
                onRecoverableError("create independent ten-band DSP", failure)
                runCatching { candidate?.release() }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            unifiedDynamics?.let { engine ->
                try {
                    engine.apply(settings)
                    capabilities.value = capsuleTenBandCapabilities()
                    engineMode.value = if (settings.enabled)
                        CapsuleEqEngineMode.UNIFIED else CapsuleEqEngineMode.OFF
                    return
                } catch (failure: Exception) {
                    onRecoverableError("update ten-band DSP", failure)
                    runCatching { engine.release() }
                    unifiedDynamics = null
                    attemptedUnifiedDynamics = true
                }
            }
        }

        // API 26-27 / OEM fallback: transform logical ten-band settings
        // onto actual hardware centre frequencies. Do NOT claim ten real
        // physical bands when Android only exposes five.
        capabilities.value = nativeCapabilities
        val eq = equalizer
        val caps = nativeCapabilities
        if (eq == null || caps == null) {
            engineMode.value = if (!settings.enabled) CapsuleEqEngineMode.OFF
                else CapsuleEqEngineMode.UNAVAILABLE
            return
        }
        if (!settings.enabled) {
            if (appliedEnabled == true) {
                runCatching { eq.enabled = false }
                    .onSuccess {
                        appliedEnabled = false
                        appliedBandLevels = intArrayOf()
                    }
                    .onFailure { onRecoverableError("bypass native EQ", it) }
            }
            engineMode.value = CapsuleEqEngineMode.OFF
            return
        }

        val levels = capsuleUnifiedEqLevels(settings, caps)
        val nonFlat = levels.any { it != 0 }
        // Configure bands while disabled to avoid temporarily applying an
        // unprotected high-gain contour on legacy devices.
        if (appliedBandLevels.size != caps.bandCount)
            appliedBandLevels = IntArray(caps.bandCount) { Int.MIN_VALUE }
        var invalidGraph = false
        for (band in 0 until caps.bandCount) {
            val gain = levels[band].coerceIn(caps.minBandLevelMb, caps.maxBandLevelMb)
            if (appliedBandLevels[band] != gain) {
                runCatching { eq.setBandLevel(band.toShort(), gain.toShort()) }
                    .onSuccess { appliedBandLevels[band] = gain }
                    .onFailure {
                        invalidGraph = true
                        onRecoverableError("update native EQ band", it)
                    }
            }
        }
        if (invalidGraph) {
            // Never apply a partially configured vendor EQ. Both the signal
            // level and the shape may differ from the intended safe contour.
            runCatching { eq.enabled = false }
                .onFailure { onRecoverableError("disable incomplete EQ", it) }
            appliedEnabled = false
            appliedBandLevels = intArrayOf()
            engineMode.value = CapsuleEqEngineMode.UNAVAILABLE
            return
        }
        if (nonFlat && appliedEnabled != true) {
            val enabled = runCatching {
                eq.enabled = true
                eq.enabled
            }.onFailure { onRecoverableError("enable native EQ", it) }
                .getOrDefault(false)
            if (enabled) appliedEnabled = true
        }
        engineMode.value = if (nonFlat && appliedEnabled != true)
            CapsuleEqEngineMode.UNAVAILABLE else CapsuleEqEngineMode.NATIVE_FALLBACK
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
