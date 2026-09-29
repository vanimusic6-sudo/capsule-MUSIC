/*
 * Capsule MUSIC
 * Shared zero-cost timeline for the play/pause comet.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.isActive

private const val CapsuleCometPeriodMs = 8_000L
private const val CapsuleCometDegreesPerMs = 360f / CapsuleCometPeriodMs

/**
 * A mathematical phase clock, not a frame clock.
 *
 * Hidden surfaces do no animation work at all. While playback is running we only remember an angle
 * plus the monotonic timestamp it belongs to; the next visible surface can reconstruct the phase in
 * O(1). That gives full-player -> mini -> full-player and player <-> lyrics a continuous comet
 * without asking Compose for a single hidden frame.
 */
internal object CapsuleCometPhaseClock {
    private var anchorAngle = 0f
    private var anchorTimeMs = SystemClock.elapsedRealtime()
    private var running = false

    @Synchronized
    fun setRunning(value: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (running) {
            anchorAngle = normalized(
                anchorAngle + (now - anchorTimeMs).coerceAtLeast(0L) * CapsuleCometDegreesPerMs,
            )
        }
        anchorTimeMs = now
        running = value
    }

    @Synchronized
    fun angleNow(): Float {
        if (!running) return normalized(anchorAngle)
        val now = SystemClock.elapsedRealtime()
        return normalized(
            anchorAngle + (now - anchorTimeMs).coerceAtLeast(0L) * CapsuleCometDegreesPerMs,
        )
    }

    private fun normalized(value: Float): Float {
        val modulo = value % 360f
        return if (modulo < 0f) modulo + 360f else modulo
    }
}

/**
 * A visible renderer for [CapsuleCometPhaseClock].
 *
 * The Animatable exists only to make the currently visible comet smooth at display refresh rate.
 * It stops instantly when hidden; on the next reveal it snaps to the shared mathematical phase
 * before drawing, so there is no restart and no hidden animation cost.
 */
@Composable
internal fun rememberCapsuleCometRotation(
    isPlaying: Boolean,
    isLoading: Boolean,
    visible: Boolean,
): Animatable<Float, AnimationVector1D> {
    val shouldTurn = orbitShouldTurn(isPlaying, isLoading, visible = true)
    val rotation =
        remember {
            Animatable(CapsuleCometPhaseClock.angleNow())
        }

    LaunchedEffect(shouldTurn, visible) {
        CapsuleCometPhaseClock.setRunning(shouldTurn)

        if (!visible) return@LaunchedEffect

        rotation.snapTo(CapsuleCometPhaseClock.angleNow())

        if (shouldTurn) {
            while (isActive) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec =
                        tween(
                            durationMillis = CapsuleCometPeriodMs.toInt(),
                            easing = LinearEasing,
                        ),
                )
                rotation.snapTo(rotation.value % 360f)
            }
        }
    }

    return rotation
}
