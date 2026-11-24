/*
 * Capsule Music — musical speed/pitch coupling.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * A classic tape-like sped-up/slowed character raises/lowers tempo AND
 * pitch together. Media3's Sonic algorithm approximates this combination;
 * it is not convolution reverb, a sample-rate change, or hardware calibration.
 */
package com.nikhil.yt.playback

internal const val CAPSULE_TEMPO_NORMAL = "normal"
internal const val CAPSULE_TEMPO_SPEED_UP = "speed_up"
internal const val CAPSULE_TEMPO_SLOWED = "slowed"

internal data class CapsuleTempoParams(val speed: Float, val pitch: Float)

internal fun capsuleTempoParams(mode: String?, intensity: Int = 50): CapsuleTempoParams {
    val weight = intensity.coerceIn(0, 100) / 100f
    val factor = when (mode) {
        CAPSULE_TEMPO_SPEED_UP -> 1.10f + 0.12f * weight // 1.16x classic
        CAPSULE_TEMPO_SLOWED -> 0.90f - 0.10f * weight // 0.85x classic
        else -> 1f
    }
    return CapsuleTempoParams(factor, factor)
}
