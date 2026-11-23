/*
 * Capsule MUSIC — Safe Listening Guard.
 * GPL-3.0. Pure gain policy and Android routing helper.
 *
 * NOT an acoustic dBA meter. No microphone access and no user health profile.
 * WHO-ITU's 75/80 dBA reference levels require calibrated headphone SPL.
 */
package com.nikhil.yt.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.pow

internal const val SAFE_LISTENING_OVERRIDE_MS = 20 * 60 * 1000L
// A conservative upper bound on app-side amplitude when the device output
// is at nominal maximum. Do NOT display this number as acoustic dBA.
internal const val SAFE_LISTENING_RELATIVE_CEILING = 0.60f

internal fun safeListeningOverrideActive(untilMs: Long, nowMs: Long): Boolean =
    untilMs > nowMs && untilMs - nowMs <= SAFE_LISTENING_OVERRIDE_MS

/**
 * Returns the highest player PCM gain permitted by the *extra* software guard.
 * Sound-pressure limits cannot be enforced without headphone calibration.
 *
 * No attenuation occurs at moderate hardware volume or when the current
 * player's signal is below this cap. A timeout override restores unity.
 */
internal fun safeListeningAppVolumeCeiling(
    headphoneRoute: Boolean,
    enabled: Boolean,
    overrideUntilMs: Long,
    nowMs: Long,
    deviceOutputAmplitude: Float,
): Float {
    if (!enabled || !headphoneRoute ||
        safeListeningOverrideActive(overrideUntilMs, nowMs)
    ) return 1f
    if (!deviceOutputAmplitude.isFinite() || deviceOutputAmplitude <= 0f)
        return SAFE_LISTENING_RELATIVE_CEILING
    return (SAFE_LISTENING_RELATIVE_CEILING /
        deviceOutputAmplitude.coerceIn(0.001f, 1f)).coerceIn(0f, 1f)
}

internal fun hearingCareOutputVolume(requested: Float, ceiling: Float): Float =
    if (!requested.isFinite() || !ceiling.isFinite()) 0f
    else requested.coerceIn(0f, 1f).coerceAtMost(ceiling.coerceIn(0f, 1f))

/** Routing lookup is best-effort. Unknown output must never be called "safe". */
internal object CapsuleHeadphoneRouting {
    fun appVolumeCeiling(
        manager: AudioManager,
        enabled: Boolean,
        overrideUntilMs: Long,
        nowMs: Long,
    ): Float {
        if (!enabled || safeListeningOverrideActive(overrideUntilMs, nowMs)) return 1f
        // Some OEMs restrict routing lookups even when the platform level
        // includes this public API. Fall back to connected outputs, but never
        // claim that a connected device is proof of a calibrated dBA level.
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                manager.getAudioDevicesForAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
            }.getOrNull()
        } else null
        val outputs = routed ?: runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())
        val headphone = outputs.firstOrNull { isHeadphoneType(it.type) }
            ?: return 1f
        val index = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        val maximum = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        if (maximum <= 0 || index <= 0) return 1f

        val attenuation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            platformVolumeAmplitude(manager, index.coerceIn(0, maximum), headphone.type)
        } else null
        // On pre-P devices the volume index is only a fallback proxy, never a
        // dBA reading or even a portable linear-amplitude measurement.
        val outputAmplitude = attenuation ?: (index.toFloat() / maximum)
        return safeListeningAppVolumeCeiling(
            headphoneRoute = true,
            enabled = enabled,
            overrideUntilMs = overrideUntilMs,
            nowMs = nowMs,
            deviceOutputAmplitude = outputAmplitude,
        )
    }

    private fun isHeadphoneType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= 37 &&
                type == AudioDeviceInfo.TYPE_BLE_HEARING_AID) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                type == AudioDeviceInfo.TYPE_HEARING_AID)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun platformVolumeAmplitude(
        manager: AudioManager,
        index: Int,
        outputType: Int,
    ): Float? {
        val db = runCatching {
            manager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, outputType)
        }.getOrNull() ?: return null
        // Android returns digital attenuation from 0 dB, NOT dBA SPL.
        if (!db.isFinite()) return null
        return 10.0.pow(db.coerceIn(-90f, 0f).toDouble() / 20.0).toFloat()
    }
}
