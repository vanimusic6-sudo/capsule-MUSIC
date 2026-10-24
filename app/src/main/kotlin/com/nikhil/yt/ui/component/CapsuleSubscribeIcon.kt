package com.nikhil.yt.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.nikhil.yt.R
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

/** One reversible geometric plus-to-check morph: no spinning or overlapping secondary stems. */
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
        progress.animateTo(if (subscribed) 1f else 0f, tween(270, easing = CapsuleStandardEasing))
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
        // A plus at p=0; a connected check at p=1. The vertical arm folds
        // into the joint as the horizontal one becomes the tick. All paths
        // share one clock and stroke: no separate cross disappearing abruptly.
        val left = point(-6f - 0.4f * p, -0.1f * p)
        val joint = point(-0.7f * p, 4.9f * p)
        val right = point(6f + 1.1f * p, -5f * p)
        val top = point(-0.7f * p, -6f * (1f - p) + 4.9f * p)
        val bottom = point(-0.7f * p, 6f * (1f - p) + 4.9f * p)
        val path = Path().apply {
            moveTo(left.x, left.y); lineTo(joint.x, joint.y); lineTo(right.x, right.y)
            moveTo(top.x, top.y); lineTo(bottom.x, bottom.y)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
            (fadeIn(tween(220, easing = CapsuleStandardEasing))
                togetherWith fadeOut(tween(160, easing = CapsuleStandardEasing)))
                .using(SizeTransform(clip = false) { _, _ -> tween(250, easing = CapsuleStandardEasing) })
        },
        label = "subscription-label",
    ) { selected ->
        Text(stringResource(if (selected) R.string.subscribed else R.string.subscribe),
            style = style, fontWeight = fontWeight,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
