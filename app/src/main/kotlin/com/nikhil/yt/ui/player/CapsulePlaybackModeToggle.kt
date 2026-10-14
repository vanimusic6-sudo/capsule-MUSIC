/**
 * Capsule MUSIC
 * AUDIO / VIDEO switch for the Capsule full player.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.playback.video.CapsulePlaybackMode
import com.nikhil.yt.playback.video.CapsuleVideoPhase
import com.nikhil.yt.playback.video.CapsuleVideoPlaybackState

@Composable
internal fun CapsuleAudioVideoToggle(
    state: CapsuleVideoPlaybackState,
    textColor: Color,
    enabled: Boolean,
    onAudioClick: () -> Unit,
    onVideoClick: () -> Unit,
    modifier: Modifier = Modifier,
    lightStyle: Boolean = false,
    cosmoStyle: Boolean = false,
    lightOrder: List<CapsuleLightAvItem> = CapsuleLightAvBaseOrder,
    lightEditable: Boolean = false,
    onLightOrderChange: (List<CapsuleLightAvItem>) -> Unit = {},
    onLightOrderSettled: (List<CapsuleLightAvItem>) -> Unit = {},
    onLightEditStarted: () -> Unit = {},
) {
    /*
     * The Light switch needs the same soft family as the transport capsule without copying
     * either extreme: 18dp is too pill-like at 48dp height, while a proportional ~10dp radius
     * leaves an obvious straight wall. The tuned shell/segment pair keeps the silhouette light.
     */
    val framedStyle = lightStyle || cosmoStyle
    val shape =
        if (framedStyle) {
            RoundedCornerShape(CapsuleLightToggleRadius)
        } else {
            RoundedCornerShape(10.dp)
        }
    val segmentShape =
        if (framedStyle) {
            RoundedCornerShape(CapsuleLightToggleSegmentRadius)
        } else {
            RoundedCornerShape(10.dp)
        }
    val videoResolving =
        state.preferredMode == CapsulePlaybackMode.VIDEO &&
            state.phase in setOf(CapsuleVideoPhase.RESOLVING, CapsuleVideoPhase.PREPARING, CapsuleVideoPhase.BUFFERING, CapsuleVideoPhase.RECOVERING)
    val videoUnavailable = state.phase == CapsuleVideoPhase.UNAVAILABLE
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val nowMs by produceState(System.currentTimeMillis(), state.requestBlockedUntilMs, lifecycle) {
        value = System.currentTimeMillis()
        if (state.requestBlockedUntilMs > 0L) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = System.currentTimeMillis()
            while (state.requestBlockedUntilMs > value) {
                delay(minOf(60_000L, state.requestBlockedUntilMs - value))
                value = System.currentTimeMillis()
            }
        }
    }
    val videoRequestPaused = state.requestBlockedUntilMs > nowMs
    val expiredCooldown = state.requestBlockedUntilMs > 0L && !videoRequestPaused
    val videoRequestError = state.phase == CapsuleVideoPhase.REQUEST_ERROR && !expiredCooldown
    val blockedMinutes = ((state.requestBlockedUntilMs - nowMs + 59_999L) / 60_000L).coerceAtLeast(1L)
    val blockedText = "paused ${blockedMinutes}m"
    val videoSelected =
        state.mode == CapsulePlaybackMode.VIDEO || videoResolving
    val audioSelected = !videoSelected

    if (framedStyle) {
        BoxWithConstraints(
            modifier = modifier
                .height(CapsuleLightToggleHeight)
                .clip(shape)
                .background(textColor.copy(alpha = if (cosmoStyle) 0.025f else 0.035f))
                .then(if (cosmoStyle) Modifier.border(1.dp, textColor.copy(alpha = 0.16f), shape) else Modifier)
                .padding(CapsuleLightToggleInset),
        ) {
            CapsuleRollingSelection(
                videoSelected = videoSelected,
                videoOnRight = lightOrder.indexOf(CapsuleLightAvItem.VIDEO) != 0,
                color = textColor,
                shape = segmentShape,
                modifier = Modifier.fillMaxSize(),
            )
            CapsuleLightReorderRow(
                order = lightOrder,
                editable = lightEditable,
                weightFor = { 1f },
                onOrderChange = onLightOrderChange,
                onOrderSettled = onLightOrderSettled,
                onEditStarted = onLightEditStarted,
                // A quick horizontal drag swaps AUDIO/VIDEO internally. A stationary long press
                // bubbles to the parent MODE row and moves the whole toggle.
                longPressToDrag = false,
                modifier = Modifier.fillMaxSize(),
            ) { item ->
                when (item) {
                    CapsuleLightAvItem.AUDIO -> {
                        CapsuleModeSegment(
                            text = "audio",
                            lightStyle = true,
                            shape = segmentShape,
                            selected = audioSelected,
                            loading = false,
                            unavailable = false,
                            requestError = false,
                            enabled = enabled,
                            textColor = textColor,
                            onClick = onAudioClick,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    CapsuleLightAvItem.VIDEO -> {
                        CapsuleModeSegment(
                            lightStyle = true,
                            shape = segmentShape,
                            text = when {
                                videoRequestPaused -> blockedText
                                videoRequestError -> "error"
                                videoUnavailable -> "video n/a"
                                else -> "video"
                            },
                            selected = videoSelected,
                            loading = videoResolving,
                            unavailable = videoUnavailable,
                            requestError = videoRequestError,
                            enabled = enabled && !videoUnavailable && !videoRequestPaused,
                            textColor = textColor,
                            onClick = onVideoClick,
                            modifier = Modifier.fillMaxWidth().semantics {
                                stateDescription = if (videoRequestPaused) state.message.orEmpty() else ""
                            },
                        )
                    }
                }
            }
            }
        }
    } else {
        BoxWithConstraints(
            modifier =
                modifier
                    .width(190.dp)
                    .height(36.dp)
                    .clip(shape)
                    .background(textColor.copy(alpha = 0.018f))
                    .border(1.dp, textColor.copy(alpha = 0.18f), shape),
        ) {
            CapsuleRollingSelection(
                videoSelected = videoSelected,
                videoOnRight = true,
                color = textColor,
                shape = segmentShape,
                modifier = Modifier.fillMaxSize(),
            )
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                CapsuleModeSegment(
                    text = "AUDIO",
                    lightStyle = false,
                    shape = segmentShape,
                    selected = audioSelected,
                    loading = false,
                    unavailable = false,
                    requestError = false,
                    enabled = enabled,
                    textColor = textColor,
                    onClick = onAudioClick,
                    modifier = Modifier.weight(1f),
                )

                Box(Modifier.width(1.dp).height(18.dp).background(textColor.copy(alpha = 0.16f)))

                CapsuleModeSegment(
                    lightStyle = false,
                    shape = segmentShape,
                    text = when {
                        videoRequestPaused -> blockedText
                        videoRequestError -> "ERROR"
                        videoUnavailable -> "VIDEO N/A"
                        else -> "VIDEO"
                    },
                    selected = videoSelected,
                    loading = videoResolving,
                    unavailable = videoUnavailable,
                    requestError = videoRequestError,
                    enabled = enabled && !videoUnavailable && !videoRequestPaused,
                    textColor = textColor,
                    onClick = onVideoClick,
                    modifier = Modifier.weight(1f).semantics {
                        stateDescription = if (videoRequestPaused) state.message.orEmpty() else ""
                    },
                )
            }
        }
    }
}

@Composable
private fun CapsuleRollingSelection(
    videoSelected: Boolean,
    videoOnRight: Boolean,
    color: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val target = if (videoSelected == videoOnRight) 1f else 0f
    val position by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 275f, visibilityThreshold = 0.001f),
        label = "capsuleModeRoll",
    )
    BoxWithConstraints(modifier) {
        Box(
            Modifier
                .width(maxWidth / 2)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = position * size.width
                    rotationZ = (sin(position.toDouble() * PI) * 1.7).toFloat()
                }
                .clip(shape)
                .background(color.copy(alpha = 0.11f)),
        )
    }
}

@Composable
private fun CapsuleModeSegment(
    text: String,
    shape: Shape,
    selected: Boolean,
    loading: Boolean,
    unavailable: Boolean,
    requestError: Boolean,
    enabled: Boolean,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lightStyle: Boolean = false,
) {
    val scale by
        animateFloatAsState(
            targetValue = if (selected) 1f else 0.985f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "capsuleModeScale",
        )
    Box(
        modifier =
            modifier
                .then(
                    if (lightStyle) Modifier.fillMaxHeight() else Modifier.height(34.dp),
                )
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
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
                                selected -> 0.96f
                                else -> if (lightStyle) 0.42f else 0.46f
                            },
                    ),
                fontFamily = if (lightStyle) FontFamily.SansSerif else FontFamily.Monospace,
                fontSize = if (unavailable || requestError) 9.sp else if (lightStyle) 14.sp else 11.sp,
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

        if (selected && !lightStyle) {
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
