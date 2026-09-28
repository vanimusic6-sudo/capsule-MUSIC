package com.nikhil.yt.ui.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

/**
 * Subscribe is one physical glyph: + rotates 45 degrees into × and rotates back on unsubscribe.
 *
 * Room-backed subscription state may briefly restore just after composition. That bootstrap is
 * data restoration, not a user tap, so the short restore window still snaps to the correct state.
 */
@Composable
internal fun CapsuleSubscribeIcon(
    subscribed: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    glyphScale: Float = 1f,
) {
    val mountedAt = remember { SystemClock.uptimeMillis() }
    val progress = remember {
        Animatable(if (subscribed) 1f else 0f)
    }

    LaunchedEffect(subscribed) {
        val target = if (subscribed) 1f else 0f
        val restoringState = SystemClock.uptimeMillis() - mountedAt < 240L

        if (restoringState) {
            progress.snapTo(target)
        } else {
            progress.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 220, easing = CapsuleStandardEasing),
            )
        }
    }

    Canvas(
        modifier.graphicsLayer {
            rotationZ = 45f * progress.value
        },
    ) {
        val unit = minOf(size.width, size.height) / 24f
        val origin = Offset(
            x = (size.width - 24f * unit) / 2f,
            y = (size.height - 24f * unit) / 2f,
        )

        fun point(x: Float, y: Float): Offset =
            Offset(origin.x + x * unit, origin.y + y * unit)

        val safeGlyphScale = glyphScale.coerceIn(0.75f, 1.45f)
        val halfSpan = 6.8f * safeGlyphScale
        val stroke = 2.15f * unit * (0.92f + 0.08f * safeGlyphScale)
        drawLine(
            tint,
            point(12f - halfSpan, 12f),
            point(12f + halfSpan, 12f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            tint,
            point(12f, 12f - halfSpan),
            point(12f, 12f + halfSpan),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
