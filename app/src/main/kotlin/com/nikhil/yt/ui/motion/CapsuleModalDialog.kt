package com.nikhil.yt.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Keeps the dialog's window AND content alive for a short reverse movement.
 *
 * Previously, button selection immediately cleared the parent's showDialog
 * flag, disposing the entire window; Compose's default window fade made
 * popups only "dissolve" on close even though their entrance had physics.
 *
 * Caller-initiated dismissals must call requestClose(afterAnimation). The
 * standard onDismissRequest (system Back/outside) uses the same path.
 * No extra blur, concurrent scale clock, or per-frame layout.
 */
internal val LocalCapsuleModalProgress =
    compositionLocalOf<Animatable<Float, AnimationVector1D>?> { null }

/** Separate dismiss progression. Entry stays at 1 throughout the exit, so
 * popup closure never runs the opening trajectory backwards. */
internal val LocalCapsuleModalExit =
    compositionLocalOf<Animatable<Float, AnimationVector1D>?> { null }

internal val LocalCapsuleModalClose =
    compositionLocalOf<((() -> Unit) -> Unit)> { { completed -> completed() } }

/** Input remains shielded during the exit so the dismissed dialog can't
 * switch selection or trigger another reorder while visually fading. */
internal val LocalCapsuleModalClosing = compositionLocalOf { false }

@Composable
internal fun CapsuleMotionDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable (requestClose: (() -> Unit) -> Unit) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val exitProgress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }

    val close: (() -> Unit) -> Unit = { callback ->
        if (!closing) {
            afterClose = callback
            closing = true
        }
    }

    LaunchedEffect(closing) {
        if (closing) {
            // EXIT IS NOT REVERSED ENTRY: the former 6.5% shrink and 16dp
            // drop left a hard-edged small window that suddenly disappeared.
            // Keep entry fully resolved; content quietly dissolves while
            // moving only a few dp and shrinking imperceptibly.
            exitProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 190, easing = CapsuleExitEasing),
            )
            (afterClose ?: onDismissRequest).invoke()
        } else {
            // Keep the spring arrival from the version the user liked.
            // Only the close was defective (old parent disappeared instantly).
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.93f,
                    stiffness = 290f,
                    visibilityThreshold = 0.001f,
                ),
            )
        }
    }

    Dialog(
        onDismissRequest = { close(onDismissRequest) },
        properties = properties,
    ) {
        CompositionLocalProvider(
            LocalCapsuleModalProgress provides progress,
            LocalCapsuleModalExit provides exitProgress,
            LocalCapsuleModalClose provides close,
            LocalCapsuleModalClosing provides closing,
        ) {
            content(close)
        }
    }
}
