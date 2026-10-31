package com.nikhil.yt.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One consistent, translucent row press state, matching the artist details list.
 *
 * The overlay is inset inside the row (rather than drawn over the text or artwork)
 * and replaces the Material ripple; no separate animation/clock is started while
 * a lazy list scrolls. Press changes invalidate only drawing, not row layout.
 * The caller still owns tap, long-press, scrolling and navigation.
 */
@Composable
fun Modifier.capsulePressClickable(
    enabled: Boolean = true,
    inset: Dp = 8.dp,
    radius: Dp = 8.dp,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState()
    val color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = ActiveBoxAlpha)
    return this
        .drawBehind {
            if (pressed.value) {
                val left = inset.toPx().coerceAtMost(size.width / 2f)
                val width = (size.width - left * 2f).coerceAtLeast(0f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, 0f),
                    size = Size(width, size.height),
                    cornerRadius = CornerRadius(radius.toPx()),
                )
            }
        }
        .clickable(
            enabled = enabled,
            interactionSource = source,
            indication = null,
            onClick = onClick,
        )
}

/** Same press feedback for rows that also open their More menu on a long hold. */
@Composable
fun Modifier.capsulePressCombinedClickable(
    enabled: Boolean = true,
    inset: Dp = 8.dp,
    radius: Dp = 8.dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState()
    val color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = ActiveBoxAlpha)
    return this
        .drawBehind {
            if (pressed.value) {
                val left = inset.toPx().coerceAtMost(size.width / 2f)
                val width = (size.width - left * 2f).coerceAtLeast(0f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, 0f),
                    size = Size(width, size.height),
                    cornerRadius = CornerRadius(radius.toPx()),
                )
            }
        }
        .combinedClickable(
            enabled = enabled,
            interactionSource = source,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
}
