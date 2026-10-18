package com.nikhil.yt.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Capsule switch with Material3's visual metrics: 52 x 32 dp track, 16 -> 24
 * dp empty thumb, and the same scheme colors. Only its MOTION differs.
 *
 * Standard Material3 Switch has a fixed internal thumb timing and can't be
 * given an animationSpec. Reimplement the tiny draw surface so a SINGLE
 * interruption-safe spring controls track, thumb position and thumb radius.
 * No layout/measure changes during animation, no thumb check icon, no ripple,
 * no extra graphicsLayer, and no frame clock while idle.
 */
@Composable
internal fun CapsuleSoftSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 680f,
            visibilityThreshold = 0.001f,
        ),
        label = "capsuleSwitchSlide",
    )

    Canvas(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(width = 52.dp, height = 32.dp)
            .toggleable(
                value = checked,
                enabled = isEnabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        val amount = motion.value.coerceIn(0f, 1f)
        val physicalPosition = motion.value.coerceIn(-0.075f, 1.075f)
        val enabledAlpha = if (isEnabled) 1f else 0.38f
        val background = lerp(
            scheme.surfaceContainerHighest,
            scheme.primary,
            amount,
        ).copy(alpha = enabledAlpha)
        val border = scheme.outline.copy(alpha = (1f - amount) * enabledAlpha)
        val thumbColor = lerp(scheme.outline, scheme.onPrimary, amount)
            .copy(alpha = enabledAlpha)
        val trackRadius = 16.dp.toPx()
        drawRoundRect(
            color = background,
            size = size,
            cornerRadius = CornerRadius(trackRadius),
        )
        if (amount < 1f) {
            drawRoundRect(
                color = border,
                size = size,
                cornerRadius = CornerRadius(trackRadius),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // The same QUICK under-damped spring briefly passes the destination.
        // Let its position overshoot by at most 1.5dp; don't clamp the
        // physics at 0/1 or the user can't feel its return impulse.
        // Squash the thumb very slightly at peak travel, with NO second
        // animation/clock. Interruption mid-flight stays continuous.
        val x = (16f + 20f * physicalPosition).dp.toPx()
        val compression = sin(PI * amount.toDouble()).toFloat().coerceAtLeast(0f)
        val thumbRadius = 8f + 4f * amount
        val horizontalRadiusPx = (thumbRadius + 1.35f * compression).dp.toPx()
        val verticalRadiusPx = (thumbRadius - 0.65f * compression).dp.toPx()
        drawOval(
            color = thumbColor,
            topLeft = Offset(x - horizontalRadiusPx, size.height / 2f - verticalRadiusPx),
            size = Size(horizontalRadiusPx * 2f, verticalRadiusPx * 2f),
        )
    }
}
