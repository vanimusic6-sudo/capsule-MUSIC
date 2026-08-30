
 /** Capsule MUSIC
 * Styled AUDIO / VIDEO switch for Capsule Player.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.playback.video.CapsulePlaybackMode
import com.nikhil.yt.playback.video.CapsuleVideoPhase
import com.nikhil.yt.playback.video.CapsuleVideoPlaybackState

@Composable
fun CapsuleAudioVideoToggle(
    state: CapsuleVideoPlaybackState,
    textColor: Color,
    enabled: Boolean,
    onAudioClick: () -> Unit,
    onVideoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val resolving =
        state.mode == CapsulePlaybackMode.VIDEO &&
            state.phase == CapsuleVideoPhase.RESOLVING
    val failed = state.phase == CapsuleVideoPhase.UNAVAILABLE

    Row(
        modifier =
            modifier
                .widthIn(min = 176.dp, max = 210.dp)
                .height(40.dp)
                .clip(shape)
                .background(textColor.copy(alpha = 0.038f))
                .border(
                    width = 1.dp,
                    color = textColor.copy(alpha = 0.15f),
                    shape = shape,
                )
                .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsuleModeSegment(
            title = "AUDIO",
            selected = state.mode == CapsulePlaybackMode.AUDIO,
            loading = false,
            failed = false,
            enabled = enabled,
            textColor = textColor,
            onClick = onAudioClick,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(3.dp))

        CapsuleModeSegment(
            title = "VIDEO",
            selected = state.mode == CapsulePlaybackMode.VIDEO,
            loading = resolving,
            failed = failed,
            enabled = enabled,
            textColor = textColor,
            onClick = onVideoClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CapsuleModeSegment(
    title: String,
    selected: Boolean,
    loading: Boolean,
    failed: Boolean,
    enabled: Boolean,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedScale by
        animateFloatAsState(
            targetValue = if (selected) 1f else 0.985f,
            animationSpec = tween(180),
            label = "capsuleModeScale",
        )

    val pulseTransition = rememberInfiniteTransition(label = "capsuleVideoPulse")
    val pulse by
        pulseTransition.animateFloat(
            initialValue = 0.46f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(680),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "capsuleVideoPulseAlpha",
        )

    val segmentShape = RoundedCornerShape(15.dp)

    Box(
        modifier =
            modifier
                .height(34.dp)
                .graphicsLayer {
                    scaleX = selectedScale
                    scaleY = selectedScale
                }
                .clip(segmentShape)
                .background(
                    if (selected) {
                        textColor.copy(alpha = 0.115f)
                    } else {
                        Color.Transparent
                    },
                )
                .clickable(
                    enabled = enabled,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected || loading || failed) {
                Box(
                    modifier =
                        Modifier
                            .size(5.dp)
                            .graphicsLayer {
                                alpha = if (loading) pulse else if (failed) 0.5f else 0.88f
                                scaleX = if (loading) 0.82f + (pulse * 0.18f) else 1f
                                scaleY = if (loading) 0.82f + (pulse * 0.18f) else 1f
                            }
                            .background(
                                textColor,
                                CircleShape,
                            ),
                )
                Spacer(Modifier.width(7.dp))
            }

            Crossfade(
                targetState =
                    when {
                        loading -> "VIDEO…"
                        failed && !selected -> "VIDEO"
                        else -> title
                    },
                animationSpec = tween(160),
                label = "capsuleModeText",
            ) { label ->
                androidx.compose.material3.Text(
                    text = label,
                    color =
                        textColor.copy(
                            alpha =
                                when {
                                    !enabled -> 0.30f
                                    selected -> 0.94f
                                    else -> 0.58f
                                },
                        ),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
