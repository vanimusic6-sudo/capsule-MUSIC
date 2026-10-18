package com.nikhil.yt.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
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

internal val LocalCapsuleModalClose =
    compositionLocalOf<((() -> Unit) -> Unit)> { { completed -> completed() } }

@Composable
internal fun CapsuleMotionDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable (requestClose: (() -> Unit) -> Unit) -> Unit,
) {
    val progress = remember { Animatable(0f) }
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
            // Reverse the same physical approach as opening. Crucially, do
            // not dispose at mid-scale or use a fade-only dismiss.
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 165, easing = CapsuleExitEasing),
            )
            (afterClose ?: onDismissRequest).invoke()
        } else {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 235, easing = CapsuleEnterEasing),
            )
        }
    }

    Dialog(
        onDismissRequest = { close(onDismissRequest) },
        properties = properties,
    ) {
        CompositionLocalProvider(
            LocalCapsuleModalProgress provides progress,
            LocalCapsuleModalClose provides close,
        ) {
            content(close)
        }
    }
}
