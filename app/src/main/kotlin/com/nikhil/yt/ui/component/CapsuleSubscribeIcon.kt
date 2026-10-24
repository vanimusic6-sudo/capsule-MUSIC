package com.nikhil.yt.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.nikhil.yt.R
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

/** One reversible glyph: the horizontal plus arms bend into a check as the vertical arm retracts. */
@Composable
internal fun CapsuleSubscribeIcon(
    subscribed: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    glyphScale: Float = 1f,
    plusSpanScale: Float = 1f,
    crossSpanScale: Float = 1f,
    strokeScale: Float = 1f,
    flattenAlpha: Boolean = false,
) {
    val progress = remember { Animatable(if (subscribed) 1f else 0f) }
    LaunchedEffect(subscribed) {
        progress.animateTo(if (subscribed) 1f else 0f, tween(320, easing = CapsuleStandardEasing))
    }
    Canvas(modifier.graphicsLayer {
        if (flattenAlpha) {
            alpha = tint.alpha
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }) {
        val p = progress.value.coerceIn(0f, 1f)
        val unit = minOf(size.width, size.height) / 24f
        val span = glyphScale.coerceIn(0.75f, 1.45f) *
            (plusSpanScale + (crossSpanScale - plusSpanScale) * p).coerceIn(0.65f, 1.25f)
        fun point(x: Float, y: Float) = center + Offset(x * unit * span, y * unit * span)
        val color = if (flattenAlpha) tint.copy(alpha = 1f) else tint
        val stroke = 2.15f * unit * strokeScale.coerceIn(0.65f, 1.2f)
        val a = point(-6.8f + 0.8f * p, -0.5f * p)
        val b = point(-1.5f * p, 5f * p)
        val c = point(6.8f + 0.7f * p, -5.5f * p)
        drawLine(color, a, b, stroke, StrokeCap.Round)
        drawLine(color, b, c, stroke, StrokeCap.Round)
        val remaining = (1f - p * 1.8f).coerceAtLeast(0f)
        if (remaining > 0f) {
            drawLine(color.copy(alpha = color.alpha * remaining), point(0f, -6.8f * remaining),
                point(0f, 6.8f * remaining), stroke, StrokeCap.Round)
        }
    }
}

/** AnimatedContent owns label measurement, so adjacent content follows its width without a snap. */
@Composable
internal fun CapsuleSubscribeLabel(
    subscribed: Boolean, modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current, fontWeight: FontWeight? = null,
) {
    AnimatedContent(
        targetState = subscribed,
        modifier = modifier,
        transitionSpec = {
            ((fadeIn(tween(200)) + slideInVertically(tween(320, easing = CapsuleStandardEasing)) { it / 5 })
                togetherWith (fadeOut(tween(140)) + slideOutVertically(tween(320, easing = CapsuleStandardEasing)) { -it / 5 }))
                .using(SizeTransform(clip = false) { _, _ -> tween(320, easing = CapsuleStandardEasing) })
        },
        label = "subscription-label",
    ) { selected ->
        Text(stringResource(if (selected) R.string.subscribed else R.string.subscribe),
            style = style, fontWeight = fontWeight,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
