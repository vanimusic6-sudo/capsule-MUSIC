/*
 * Capsule MUSIC
 * YouTube Music-inspired AUDIO / VIDEO switch.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
    val resolvingVideo =
        state.mode == CapsulePlaybackMode.VIDEO &&
            state.phase == CapsuleVideoPhase.RESOLVING

    val shape = RoundedCornerShape(19.dp)

    Row(
        modifier =
            modifier
                .widthIn(
                    min = 170.dp,
                    max = 190.dp,
                )
                .height(38.dp)
                .clip(shape)
                .background(
                    textColor.copy(alpha = 0.035f),
                )
                .border(
                    width = 1.dp,
                    color = textColor.copy(alpha = 0.14f),
                    shape = shape,
                )
                .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsuleModeSegment(
            title = "AUDIO",
            selected = state.mode == CapsulePlaybackMode.AUDIO,
            loading = false,
            enabled = enabled,
            textColor = textColor,
            onClick = onAudioClick,
            modifier = Modifier.weight(1f),
        )

        Spacer(
            Modifier.width(3.dp),
        )

        CapsuleModeSegment(
            title = "VIDEO",
            selected = state.mode == CapsulePlaybackMode.VIDEO,
            loading = resolvingVideo,
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
    enabled: Boolean,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedAmount by
        animateFloatAsState(
            targetValue = if (selected) 1f else 0f,
            animationSpec = tween(190),
            label = "capsuleModeSelected",
        )

    val scale by
        animateFloatAsState(
            targetValue = if (selected) 1f else 0.985f,
            animationSpec = tween(190),
            label = "capsuleModeScale",
        )

    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier =
            modifier
                .height(32.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(
                    textColor.copy(
                        alpha = 0.12f * selectedAmount,
                    ),
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
            if (loading) {
                CircularProgressIndicator(
                    modifier =
                        Modifier.size(11.dp),
                    color =
                        textColor.copy(alpha = 0.88f),
                    strokeWidth =
                        1.5.dp,
                )

                Spacer(
                    Modifier.width(7.dp),
                )
            }

            Crossfade(
                targetState =
                    if (loading) {
                        "VIDEO"
                    } else {
                        title
                    },
                animationSpec = tween(150),
                label = "capsuleModeText",
            ) { label ->
                Text(
                    text = label,
                    color =
                        textColor.copy(
                            alpha =
                                when {
                                    !enabled -> 0.30f
                                    selected -> 0.94f
                                    else -> 0.56f
                                },
                        ),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    fontWeight =
                        if (selected) {
                            FontWeight.Bold
                        } else {
                            FontWeight.SemiBold
                        },
                    letterSpacing = 0.65.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
