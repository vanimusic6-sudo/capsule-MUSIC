/**
 * Capsule MUSIC
 * AUDIO / VIDEO switch for the Capsule full player.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
    val shape = RoundedCornerShape(10.dp)
    val videoResolving =
        state.preferredMode == CapsulePlaybackMode.VIDEO &&
            state.phase == CapsuleVideoPhase.RESOLVING
    val videoUnavailable =
        state.preferredMode == CapsulePlaybackMode.VIDEO &&
            state.phase == CapsuleVideoPhase.UNAVAILABLE
    val videoRequestError =
        state.preferredMode == CapsulePlaybackMode.VIDEO &&
            state.phase == CapsuleVideoPhase.REQUEST_ERROR
    val videoSelected =
        state.mode == CapsulePlaybackMode.VIDEO || videoResolving
    val audioSelected = !videoSelected

    Row(
        modifier =
            modifier
                .width(190.dp)
                .height(36.dp)
                .clip(shape)
                .background(textColor.copy(alpha = 0.018f))
                .border(1.dp, textColor.copy(alpha = 0.18f), shape)
                .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsuleModeSegment(
            text = "AUDIO",
            selected = audioSelected,
            loading = false,
            unavailable = false,
            requestError = false,
            enabled = enabled,
            textColor = textColor,
            onClick = onAudioClick,
            modifier = Modifier.weight(1f),
        )

        Box(
            Modifier
                .width(1.dp)
                .height(18.dp)
                .background(textColor.copy(alpha = 0.16f)),
        )

        CapsuleModeSegment(
            text =
                when {
                    videoRequestError -> "VIDEO ERROR"
                    videoUnavailable -> "VIDEO N/A"
                    else -> "VIDEO"
                },
            selected = videoSelected,
            loading = videoResolving,
            unavailable = videoUnavailable,
            requestError = videoRequestError,
            enabled = enabled && !videoUnavailable,
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
    unavailable: Boolean,
    requestError: Boolean,
    enabled: Boolean,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by
        animateFloatAsState(
            targetValue = if (selected) 1f else 0.985f,
            animationSpec = tween(160),
            label = "capsuleModeScale",
        )

    Box(
        modifier =
            modifier
                .height(34.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(
                    enabled = enabled && !loading,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                color =
                    textColor.copy(
                        alpha =
                            when {
                                unavailable -> 0.24f
                                requestError -> 0.56f
                                !enabled -> 0.28f
                                selected -> 0.94f
                                else -> 0.46f
                            },
                    ),
                fontFamily = FontFamily.Monospace,
                fontSize = if (unavailable || requestError) 9.sp else 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.35.sp,
                maxLines = 1,
            )

            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.width(11.dp).height(11.dp),
                    strokeWidth = 1.3.dp,
                    color = textColor.copy(alpha = 0.72f),
                )
            }
        }

        if (selected) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .width(56.dp)
                        .height(2.dp)
                        .background(
                            textColor.copy(alpha = 0.86f),
                            RoundedCornerShape(2.dp),
                        ),
            )
        }
    }
}
