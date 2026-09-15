
/* * Velune / Capsule MUSIC
 * Capsule Lyrics visual layer
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.component.Lyrics
import com.nikhil.yt.utils.makeTimeString
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

private val CapsuleLyricsBackground =
    Color(0xFF101010)

private val CapsuleLyricsText =
    Color(0xFFF0F0F0)

private val CapsuleLyricsSecondary =
    Color(0xFF858585)

private val CapsuleLyricsOutline =
    Color(0xFF343434)

private val CapsuleLyricsPanel =
    Color(0xFF171717)

private val CapsuleLyricsPanelShape =
    RoundedCornerShape(24.dp)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapsuleLyricsContent(
    mediaMetadata: MediaMetadata,
    sliderPosition: Long?,
    positionMs: Long,
    durationMs: Long,
    onClose: () -> Unit,
    onMenuClick: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerConnection =
        LocalPlayerConnection.current ?: return

    val player =
        playerConnection.player

    val playbackState by
        playerConnection.playbackState.collectAsState()

    val isPlaying by
        playerConnection.isPlaying.collectAsState()

    val canSkipPrevious by
        playerConnection.canSkipPrevious.collectAsState()

    val canSkipNext by
        playerConnection.canSkipNext.collectAsState()

    val volumeState =
        playerConnection
            .service
            .playerVolume
            .collectAsState()

    val isLoading =
        playbackState ==
            Player.STATE_BUFFERING ||
            sliderPosition != null

    /*
     * LyricsScreen is created only when the transition begins. Its own
     * position/duration state used to start at 0 / TIME_UNSET for one frame,
     * which made the progress line flash. Use the live ExoPlayer values as
     * the first-frame fallback so the line appears at the correct position
     * immediately.
     */
    val liveDuration =
        player.duration

    val durationInputValid =
        durationMs > 0L &&
            durationMs != C.TIME_UNSET

    val safeDuration =
        when {
            durationInputValid ->
                durationMs

            liveDuration > 0L &&
                liveDuration != C.TIME_UNSET ->
                liveDuration

            else ->
                0L
        }

    val displayPosition =
        (
            sliderPosition
                ?: if (durationInputValid) {
                    positionMs
                } else {
                    player.currentPosition
                }
        ).coerceAtLeast(0L)

    val remaining =
        (
            safeDuration -
                displayPosition
        ).coerceAtLeast(0L)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    CapsuleLyricsBackground,
                )
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Top +
                            WindowInsetsSides.Horizontal +
                            WindowInsetsSides.Bottom,
                    ),
                )
                .padding(
                    horizontal = 18.dp,
                    vertical = 8.dp,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .align(
                            Alignment.CenterStart,
                        )
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(
                            onClick = onClose,
                        ),
                contentAlignment =
                    Alignment.Center,
            ) {
                Icon(
                    painter =
                        painterResource(
                            R.drawable.expand_more,
                        ),
                    contentDescription = null,
                    tint =
                        CapsuleLyricsText,
                    modifier =
                        Modifier.size(30.dp),
                )
            }

            Column(
                modifier =
                    Modifier
                        .align(
                            Alignment.Center,
                        )
                        .padding(
                            horizontal = 60.dp,
                        ),
                horizontalAlignment =
                    Alignment.CenterHorizontally,
            ) {
                Text(
                    text =
                        mediaMetadata.title,
                    color =
                        CapsuleLyricsText,
                    fontSize = 19.sp,
                    lineHeight = 22.sp,
                    fontWeight =
                        FontWeight.SemiBold,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis,
                )

                Text(
                    text =
                        mediaMetadata
                            .artists
                            .joinToString {
                                it.name
                            },
                    color =
                        CapsuleLyricsSecondary,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontFamily =
                        FontFamily.Monospace,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier =
                    Modifier
                        .align(
                            Alignment.CenterEnd,
                        )
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(
                            onClick =
                                onMenuClick,
                        ),
                contentAlignment =
                    Alignment.Center,
            ) {
                Icon(
                    painter =
                        painterResource(
                            R.drawable.more_horiz,
                        ),
                    contentDescription = null,
                    tint =
                        CapsuleLyricsText,
                    modifier =
                        Modifier.size(28.dp),
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        top = 6.dp,
                        bottom = 8.dp,
                    ),
        ) {
            Lyrics(
                sliderPositionProvider = {
                    sliderPosition
                },
            )
        }

        CapsuleThinSlider(
            value =
                displayPosition
                    .toFloat(),
            valueRange =
                0f..
                    safeDuration
                        .coerceAtLeast(1L)
                        .toFloat(),
            enabled =
                safeDuration > 0L,
            activeColor =
                CapsuleLyricsText,
            inactiveColor =
                CapsuleLyricsText.copy(
                    alpha = 0.22f,
                ),
            onValueChange = {
                onSeekPreview(
                    it.toLong(),
                )
            },
            onValueChangeFinished =
                onSeekFinished,
            trackHeight =
                5.5.dp,
            thumbRadius =
                4.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(28.dp),
        )

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween,
        ) {
            Text(
                text =
                    makeTimeString(
                        displayPosition,
                    ),
                color =
                    CapsuleLyricsSecondary,
                fontFamily =
                    FontFamily.Monospace,
                fontSize = 13.sp,
            )

            Text(
                text =
                    if (safeDuration > 0L) {
                        "-${
                            makeTimeString(
                                remaining,
                            )
                        }"
                    } else {
                        ""
                    },
                color =
                    CapsuleLyricsSecondary,
                fontFamily =
                    FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }

        Spacer(
            Modifier.height(12.dp),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(
                        CapsuleLyricsPanelShape,
                    )
                    .border(
                        width = 1.dp,
                        color =
                            CapsuleLyricsOutline,
                        shape =
                            CapsuleLyricsPanelShape,
                    )
                    .background(
                        CapsuleLyricsPanel,
                    ),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(86.dp),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                CapsuleLyricsSideButton(
                    iconRes =
                        R.drawable.skip_previous,
                    enabled =
                        canSkipPrevious,
                    onClick =
                        playerConnection::seekToPrevious,
                    modifier =
                        Modifier.weight(1f),
                )

                Box(
                    modifier =
                        Modifier.weight(1.2f),
                    contentAlignment =
                        Alignment.Center,
                ) {
                    CapsuleLyricsOrbitButton(
                        isPlaying =
                            isPlaying,
                        isLoading =
                            isLoading,
                        onClick = {
                            if (
                                playbackState ==
                                Player.STATE_ENDED
                            ) {
                                playerConnection
                                    .player
                                    .seekTo(
                                        0,
                                        0,
                                    )

                                playerConnection
                                    .player
                                    .playWhenReady =
                                    true
                            } else {
                                playerConnection
                                    .player
                                    .togglePlayPause()
                            }
                        },
                    )
                }

                CapsuleLyricsSideButton(
                    iconRes =
                        R.drawable.skip_next,
                    enabled =
                        canSkipNext,
                    onClick =
                        playerConnection::seekToNext,
                    modifier =
                        Modifier.weight(1f),
                )
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            CapsuleLyricsOutline,
                        ),
            )

            /*
             * Simple volume row: no nested card, no extra border.
             * Just two quiet icons and a thin Capsule line.
             */
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .padding(
                            horizontal = 14.dp,
                        ),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                Icon(
                    painter =
                        painterResource(
                            R.drawable.volume_off,
                        ),
                    contentDescription = null,
                    tint =
                        CapsuleLyricsSecondary,
                    modifier =
                        Modifier
                            .size(18.dp)
                            .clickable {
                                playerConnection
                                    .service
                                    .playerVolume
                                    .value =
                                    0f
                            },
                )

                CapsuleThinSlider(
                    value =
                        volumeState.value
                            .coerceIn(
                                0f,
                                1f,
                            ),
                    valueRange =
                        0f..1f,
                    enabled =
                        true,
                    activeColor =
                        CapsuleLyricsText.copy(
                            alpha = 0.70f,
                        ),
                    inactiveColor =
                        CapsuleLyricsText.copy(
                            alpha = 0.11f,
                        ),
                    onValueChange = {
                        playerConnection
                            .service
                            .playerVolume
                            .value =
                            it
                    },
                    onValueChangeFinished = {},
                    trackHeight =
                        2.5.dp,
                    thumbRadius =
                        3.dp,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(20.dp)
                            .padding(
                                horizontal = 12.dp,
                            ),
                )

                Icon(
                    painter =
                        painterResource(
                            R.drawable.volume_up,
                        ),
                    contentDescription = null,
                    tint =
                        CapsuleLyricsSecondary,
                    modifier =
                        Modifier
                            .size(18.dp)
                            .clickable {
                                playerConnection
                                    .service
                                    .playerVolume
                                    .value =
                                    1f
                            },
                )
            }
        }

        Spacer(
            Modifier.height(10.dp),
        )
    }
}


@Composable
private fun CapsuleThinSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
    thumbRadius: Dp = 4.dp,
) {
    val rangeSize =
        (
            valueRange.endInclusive -
                valueRange.start
        ).coerceAtLeast(0.0001f)

    val fraction =
        (
            (value - valueRange.start) /
                rangeSize
        ).coerceIn(0f, 1f)

    Canvas(
        modifier =
            modifier
                .pointerInput(
                    enabled,
                    valueRange.start,
                    valueRange.endInclusive,
                ) {
                    if (!enabled) {
                        return@pointerInput
                    }

                    val widthPx =
                        size.width
                            .toFloat()
                            .coerceAtLeast(1f)

                    val insetPx =
                        thumbRadius.toPx()

                    val usableWidth =
                        (
                            widthPx -
                                insetPx * 2f
                        ).coerceAtLeast(1f)

                    fun updateFromX(
                        x: Float,
                    ) {
                        val newFraction =
                            (
                                (x - insetPx) /
                                    usableWidth
                            ).coerceIn(0f, 1f)

                        onValueChange(
                            valueRange.start +
                                rangeSize *
                                newFraction,
                        )
                    }

                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed =
                                    false,
                            )

                        updateFromX(
                            down.position.x,
                        )

                        down.consume()

                        while (true) {
                            val event =
                                awaitPointerEvent()

                            val change =
                                event.changes
                                    .firstOrNull {
                                        it.id ==
                                            down.id
                                    }
                                    ?: break

                            if (!change.pressed) {
                                onValueChangeFinished()
                                break
                            }

                            updateFromX(
                                change.position.x,
                            )

                            change.consume()
                        }
                    }
                },
    ) {
        val centerY =
            size.height / 2f

        val radiusPx =
            thumbRadius.toPx()

        val startX =
            radiusPx

        val endX =
            (
                size.width -
                    radiusPx
            ).coerceAtLeast(startX)

        val activeEnd =
            startX +
                (
                    endX -
                        startX
                ) * fraction

        val resolvedActive =
            if (enabled) {
                activeColor
            } else {
                activeColor.copy(
                    alpha =
                        activeColor.alpha *
                            0.38f,
                )
            }

        val resolvedInactive =
            if (enabled) {
                inactiveColor
            } else {
                inactiveColor.copy(
                    alpha =
                        inactiveColor.alpha *
                            0.45f,
                )
            }

        drawLine(
            color =
                resolvedInactive,
            start =
                Offset(
                    startX,
                    centerY,
                ),
            end =
                Offset(
                    endX,
                    centerY,
                ),
            strokeWidth =
                trackHeight.toPx(),
            cap =
                StrokeCap.Round,
        )

        if (activeEnd > startX) {
            drawLine(
                color =
                    resolvedActive,
                start =
                    Offset(
                        startX,
                        centerY,
                    ),
                end =
                    Offset(
                        activeEnd,
                        centerY,
                    ),
                strokeWidth =
                    trackHeight.toPx(),
                cap =
                    StrokeCap.Round,
            )
        }

        drawCircle(
            color =
                resolvedActive,
            radius =
                radiusPx,
            center =
                Offset(
                    activeEnd,
                    centerY,
                ),
        )
    }
}

@Composable
private fun CapsuleLyricsSideButton(
    iconRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(86.dp)
                .clickable(
                    enabled = enabled,
                    onClick = onClick,
                ),
        contentAlignment =
            Alignment.Center,
    ) {
        Icon(
            painter =
                painterResource(iconRes),
            contentDescription = null,
            tint =
                CapsuleLyricsText.copy(
                    alpha =
                        if (enabled) {
                            0.92f
                        } else {
                            0.25f
                        },
                ),
            modifier =
                Modifier.size(32.dp),
        )
    }
}

@Composable
private fun CapsuleLyricsOrbitButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    val rotation =
        remember {
            Animatable(0f)
        }

    LaunchedEffect(
        isPlaying,
        isLoading,
    ) {
        if (
            isPlaying &&
            !isLoading
        ) {
            while (isActive) {
                rotation.animateTo(
                    targetValue =
                        rotation.value +
                            360f,
                    animationSpec =
                        tween(
                            durationMillis =
                                8_000,
                            easing =
                                LinearEasing,
                        ),
                )

                rotation.snapTo(
                    rotation.value %
                        360f,
                )
            }
        }
    }

    val alpha by
        animateFloatAsState(
            targetValue =
                if (isPlaying) {
                    1f
                } else {
                    0.46f
                },
            animationSpec =
                tween(
                    durationMillis =
                        300,
                ),
            label =
                "CapsuleLyricsOrbitAlpha",
        )

    Box(
        modifier =
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .clickable(
                    onClick = onClick,
                ),
        contentAlignment =
            Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color =
                    CapsuleLyricsText,
                strokeWidth =
                    2.dp,
                modifier =
                    Modifier.size(30.dp),
            )
        } else {
            Canvas(
                modifier =
                    Modifier.size(62.dp),
            ) {
                val cx =
                    size.width / 2f

                val cy =
                    size.height / 2f

                val rx =
                    size.width *
                        0.47f

                val ry =
                    size.height *
                        0.19f

                val orbitColor =
                    CapsuleLyricsText.copy(
                        alpha = alpha,
                    )

                rotate(-25f) {
                    drawOval(
                        color =
                            orbitColor,
                        topLeft =
                            Offset(
                                cx - rx,
                                cy - ry,
                            ),
                        size =
                            Size(
                                rx * 2f,
                                ry * 2f,
                            ),
                        style =
                            Stroke(
                                width =
                                    size.width *
                                        0.045f,
                            ),
                    )

                    val angle =
                        Math.toRadians(
                            rotation
                                .value
                                .toDouble(),
                        )

                    val previousAngle =
                        Math.toRadians(
                            (
                                rotation.value -
                                    15f
                            ).toDouble(),
                        )

                    val point =
                        Offset(
                            x =
                                cx +
                                    rx *
                                    cos(angle)
                                        .toFloat(),
                            y =
                                cy +
                                    ry *
                                    sin(angle)
                                        .toFloat(),
                        )

                    val tail =
                        Offset(
                            x =
                                cx +
                                    rx *
                                    cos(
                                        previousAngle,
                                    ).toFloat(),
                            y =
                                cy +
                                    ry *
                                    sin(
                                        previousAngle,
                                    ).toFloat(),
                        )

                    drawLine(
                        color =
                            orbitColor.copy(
                                alpha =
                                    alpha *
                                        0.35f,
                            ),
                        start = tail,
                        end = point,
                        strokeWidth =
                            size.width *
                                0.035f,
                    )

                    drawCircle(
                        color =
                            orbitColor,
                        radius =
                            size.width *
                                0.052f,
                        center =
                            point,
                    )
                }

                drawCircle(
                    color =
                        orbitColor,
                    radius =
                        size.width *
                            0.115f,
                    center =
                        Offset(
                            cx,
                            cy,
                        ),
                )
            }
        }
    }
}
