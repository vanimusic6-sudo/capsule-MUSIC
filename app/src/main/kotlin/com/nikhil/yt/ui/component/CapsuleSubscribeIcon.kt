package com.nikhil.yt.ui.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/**
 * A real geometric morph between the subscribe plus and the subscribed check mark.
 *
 * Room-backed subscription state may briefly start from `null` when a mini-player subtree is
 * restored. That bootstrap is data restoration, not user intent, so state changes during the short
 * restore window snap to the truth. Once the icon has actually been on screen, later changes keep
 * the full morph animation.
 */
@Composable
internal fun CapsuleSubscribeIcon(
    subscribed: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
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
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            )
        }
    }

    Canvas(modifier) {
        val unit = minOf(size.width, size.height) / 24f
        val origin = Offset(
            x = (size.width - 24f * unit) / 2f,
            y = (size.height - 24f * unit) / 2f,
        )

        fun lerp(start: Float, end: Float): Float = start + (end - start) * progress.value
        fun point(x: Float, y: Float): Offset =
            Offset(origin.x + x * unit, origin.y + y * unit)

        // Horizontal plus stroke -> short rising stroke of the check.
        val firstStart = point(
            lerp(5.2f, 5.4f),
            lerp(12f, 12.6f),
        )
        val firstEnd = point(
            lerp(18.8f, 10.1f),
            lerp(12f, 17.1f),
        )

        // Vertical plus stroke -> long falling stroke of the check.
        val secondStart = point(
            lerp(12f, 10.1f),
            lerp(5.2f, 17.1f),
        )
        val secondEnd = point(
            lerp(12f, 19.1f),
            lerp(18.8f, 7.2f),
        )

        val stroke = 2.15f * unit
        drawLine(tint, firstStart, firstEnd, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, secondStart, secondEnd, strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
