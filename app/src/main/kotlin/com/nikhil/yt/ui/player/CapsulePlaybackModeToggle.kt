
 /** Capsule MUSIC
 * Minimal AUDIO / VIDEO switch.
 * Keeps the original Capsule player layout untouched except for this control.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
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
    val shape = RoundedCornerShape(18.dp)

    Row(
        modifier =
            modifier
                .width(174.dp)
                .height(34.dp)
                .clip(shape)
                .background(textColor.copy(alpha = 0.025f))
                .border(1.dp, textColor.copy(alpha = 0.16f), shape)
                .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsuleModeSegment(
            text = "AUDIO",
            selected = state.mode == CapsulePlaybackMode.AUDIO,
            loading = false,
            enabled = enabled,
            textColor = textColor,
            onClick = onAudioClick,
            modifier = Modifier.weight(1f),
        )

        CapsuleModeSegment(
            text = "VIDEO",
            selected = state.mode == CapsulePlaybackMode.VIDEO,
            loading =
                state.mode == CapsulePlaybackMode.VIDEO &&
                    state.phase == CapsuleVideoPhase.RESOLVING,
            enabled = enabled,
            textColor = textColor,
            onClick = onVideoClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CapsuleModeSegment(
    text: String,
    selected: Boolean,
    loading: Boolean,
    enabled: Boolean,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by
        animateFloatAsState(
            targetValue = if (selected) 1f else 0.985f,
            animationSpec = tween(150),
            label = "capsuleModeScale",
        )

    val shape = RoundedCornerShape(15.dp)

    Box(
        modifier =
            modifier
                .height(28.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(
                    if (selected) {
                        textColor.copy(alpha = 0.13f)
                    } else {
                        Color.Transparent
                    },
                )
                .clickable(
                    enabled = enabled && !loading,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.width(13.dp).height(13.dp),
                strokeWidth = 1.5.dp,
                color = textColor.copy(alpha = 0.8f),
            )
        } else {
            Text(
                text = text,
                color =
                    textColor.copy(
                        alpha =
                            when {
                                !enabled -> 0.3f
                                selected -> 0.95f
                                else -> 0.52f
                            },
                    ),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1,
            )
        }
    }
}
