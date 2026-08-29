/**
 * Velune / Capsule MUSIC
 * Capsule full-player visual layer
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.player

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.extensions.toggleRepeatMode
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.playback.PlayerConnection
import com.nikhil.yt.utils.makeTimeString
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val CapsuleArtworkShape =
    RoundedCornerShape(24.dp)

private val CapsuleControlsShape =
    RoundedCornerShape(24.dp)

/**
 * Capsule full player adapted for Velune.
 *
 * PlayerConnection remains completely owned by Velune.
 * No MusicService, Innertube, stream resolver, cache or queue
 * implementation is replaced here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapsulePlayerContent(
    mediaMetadata: MediaMetadata,
    sliderPosition: Long?,
    positionMs: Long,
    durationMs: Long,
    textColor: Color,
    liked: Boolean,
    playerConnection: PlayerConnection,
    onToggleLike: () -> Unit,
    onExpandQueue: () -> Unit,
    onMenuClick: () -> Unit,
    context: Context,
    bottomPadding: Dp,
) {
    val isPlaying by
        playerConnection.isPlaying.collectAsState()

    val playbackState by
        playerConnection.playbackState.collectAsState()

    val canSkipPrevious by
        playerConnection.canSkipPrevious.collectAsState()

    val canSkipNext by
        playerConnection.canSkipNext.collectAsState()

    val repeatMode by
        playerConnection.repeatMode.collectAsState()

    val isLoading =
        playbackState == Player.STATE_BUFFERING

    val secondaryText =
        textColor.copy(alpha = 0.55f)

    val outline =
        textColor.copy(alpha = 0.16f)

    val panel =
        textColor.copy(alpha = 0.025f)

    val displayPosition =
        (sliderPosition ?: positionMs)
            .coerceAtLeast(0L)

    val safeDuration =
        durationMs
            .takeIf {
                it > 0L &&
                    it != C.TIME_UNSET
            }
            ?: 0L

    val remaining =
        (safeDuration - displayPosition)
            .coerceAtLeast(0L)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Top +
                            WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(
                    bottom = bottomPadding,
                ),
        horizontalAlignment =
            Alignment.CenterHorizontally,
    ) {
        /*
         * Header.
         */
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 18.dp,
                        end = 18.dp,
                        top = 10.dp,
                        bottom = 10.dp,
                    ),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            Text(
                text = "NOW PLAYING",
                color = secondaryText,
                fontFamily =
                    FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight =
                    FontWeight.SemiBold,
            )

            Text(
                text =
                    if (safeDuration > 0L) {
                        makeTimeString(
                            safeDuration,
                        )
                    } else {
                        ""
                    },
                color = secondaryText,
                fontFamily =
                    FontFamily.Monospace,
                fontSize = 15.sp,
            )
        }

        /*
         * Artwork.
         */
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        horizontal = 18.dp,
                        vertical = 6.dp,
                    ),
            contentAlignment =
                Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(
                            CapsuleArtworkShape,
                        )
                        .border(
                            width = 1.dp,
                            color = outline,
                            shape =
                                CapsuleArtworkShape,
                        )
                        .background(
                            textColor.copy(
                                alpha = 0.045f,
                            ),
                        ),
                contentAlignment =
                    Alignment.Center,
            ) {
                AsyncImage(
                    model =
                        mediaMetadata
                            .thumbnailUrl
                            ?.toHighResThumbnail(),
                    contentDescription =
                        mediaMetadata.title,
                    contentScale =
                        ContentScale.Crop,
                    modifier =
                        Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 18.dp,
                    ),
        ) {
            /*
             * Song metadata + connected Share/Like capsule.
             */
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(
                                end = 10.dp,
                            ),
                ) {
                    Text(
                        text =
                            mediaMetadata.title
                                ?: "Unknown",
                        color = textColor,
                        fontSize = 27.sp,
                        lineHeight = 31.sp,
                        fontWeight =
                            FontWeight.Bold,
                        maxLines = 1,
                        overflow =
                            TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .basicMarquee(),
                    )

                    Text(
                        text =
                            mediaMetadata
                                .artists
                                .joinToString {
                                    it.name
                                },
                        color = secondaryText,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        maxLines = 1,
                        overflow =
                            TextOverflow.Ellipsis,
                        modifier =
                            Modifier.basicMarquee(),
                    )
                }

                CapsuleShareLikeButtons(
                    textColor = textColor,
                    outline = outline,
                    panel = panel,
                    liked = liked,
                    mediaId =
                        mediaMetadata.id,
                    onToggleLike =
                        onToggleLike,
                    context = context,
                )
            }

            Spacer(
                Modifier.height(14.dp),
            )

            /*
             * Capsule progress bar.
             */
            Slider(
                value =
                    displayPosition
                        .toFloat()
                        .coerceIn(
                            0f,
                            safeDuration
                                .coerceAtLeast(1L)
                                .toFloat(),
                        ),
                valueRange =
                    0f..
                        safeDuration
                            .coerceAtLeast(1L)
                            .toFloat(),
                onValueChange = { value ->
                    if (safeDuration > 0L) {
                        playerConnection
                            .player
                            .seekTo(
                                value.toLong(),
                            )
                    }
                },
                enabled =
                    safeDuration > 0L,
                thumb = {
                    Spacer(
                        Modifier.size(0.dp),
                    )
                },
                colors =
                    SliderDefaults.colors(
                        activeTrackColor =
                            textColor.copy(
                                alpha = 0.92f,
                            ),
                        inactiveTrackColor =
                            textColor.copy(
                                alpha = 0.22f,
                            ),
                        disabledActiveTrackColor =
                            textColor.copy(
                                alpha = 0.36f,
                            ),
                        disabledInactiveTrackColor =
                            textColor.copy(
                                alpha = 0.12f,
                            ),
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp),
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 2.dp,
                        ),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
            ) {
                Text(
                    text =
                        makeTimeString(
                            displayPosition,
                        ),
                    color = secondaryText,
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
                    color = secondaryText,
                    fontFamily =
                        FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }

            Spacer(
                Modifier.height(16.dp),
            )

            /*
             * Capsule control surface.
             */
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(
                            CapsuleControlsShape,
                        )
                        .border(
                            width = 1.dp,
                            color = outline,
                            shape =
                                CapsuleControlsShape,
                        )
                        .background(panel),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(92.dp),
                    verticalAlignment =
                        Alignment.CenterVertically,
                ) {
                    CapsuleSideButton(
                        iconRes =
                            R.drawable.skip_previous,
                        enabled =
                            canSkipPrevious,
                        textColor =
                            textColor,
                        onClick =
                            playerConnection::seekToPrevious,
                        modifier =
                            Modifier.weight(1f),
                    )

                    Box(
                        modifier =
                            Modifier.weight(
                                1.18f,
                            ),
                        contentAlignment =
                            Alignment.Center,
                    ) {
                        CapsuleOrbitButton(
                            isPlaying =
                                isPlaying,
                            isLoading =
                                isLoading,
                            color =
                                textColor,
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

                    CapsuleSideButton(
                        iconRes =
                            R.drawable.skip_next,
                        enabled =
                            canSkipNext,
                        textColor =
                            textColor,
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
                                outline,
                            ),
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(66.dp),
                    verticalAlignment =
                        Alignment.CenterVertically,
                ) {
                    CapsuleAuxButton(
                        iconRes =
                            when (repeatMode) {
                                Player.REPEAT_MODE_ONE ->
                                    R.drawable.repeat_one

                                else ->
                                    R.drawable.repeat
                            },
                        tint =
                            if (
                                repeatMode ==
                                Player.REPEAT_MODE_OFF
                            ) {
                                textColor.copy(
                                    alpha = 0.46f,
                                )
                            } else {
                                textColor.copy(
                                    alpha = 0.90f,
                                )
                            },
                        onClick = {
                            playerConnection
                                .player
                                .toggleRepeatMode()
                        },
                        modifier =
                            Modifier.weight(1f),
                    )

                    CapsuleAuxButton(
                        iconRes =
                            R.drawable.more_horiz,
                        tint =
                            textColor.copy(
                                alpha = 0.88f,
                            ),
                        onClick =
                            onMenuClick,
                        modifier =
                            Modifier.weight(1f),
                    )
                }
            }

            /*
             * Queue handle.
             */
            Spacer(
                Modifier.height(14.dp),
            )

            Box(
                modifier =
                    Modifier
                        .align(
                            Alignment.CenterHorizontally,
                        )
                        .width(44.dp)
                        .height(4.dp)
                        .clip(
                            CircleShape,
                        )
                        .background(
                            textColor.copy(
                                alpha = 0.22f,
                            ),
                        )
                        .clickable(
                            onClick =
                                onExpandQueue,
                        ),
            )

            Spacer(
                Modifier.height(12.dp),
            )
        }
    }
}

@Composable
private fun CapsuleShareLikeButtons(
    textColor: Color,
    outline: Color,
    panel: Color,
    liked: Boolean,
    mediaId: String,
    onToggleLike: () -> Unit,
    context: Context,
) {
    val shareShape =
        RoundedCornerShape(
            topStart = 18.dp,
            bottomStart = 18.dp,
            topEnd = 3.dp,
            bottomEnd = 3.dp,
        )

    val favoriteShape =
        RoundedCornerShape(
            topStart = 3.dp,
            bottomStart = 3.dp,
            topEnd = 18.dp,
            bottomEnd = 18.dp,
        )

    Row(
        horizontalArrangement =
            Arrangement.spacedBy(3.dp),
    ) {
        /*
         * Share.
         */
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .clip(
                        shareShape,
                    )
                    .border(
                        width = 1.dp,
                        color = outline,
                        shape = shareShape,
                    )
                    .background(
                        panel,
                    )
                    .clickable {
                        val intent =
                            Intent(
                                Intent.ACTION_SEND,
                            ).apply {
                                type =
                                    "text/plain"

                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "https://music.youtube.com/watch?v=$mediaId",
                                )
                            }

                        context.startActivity(
                            Intent.createChooser(
                                intent,
                                null,
                            ),
                        )
                    },
            contentAlignment =
                Alignment.Center,
        ) {
            Icon(
                painter =
                    painterResource(
                        R.drawable.share,
                    ),
                contentDescription =
                    null,
                tint =
                    textColor,
                modifier =
                    Modifier.size(24.dp),
            )
        }

        /*
         * Favorite.
         */
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .clip(
                        favoriteShape,
                    )
                    .border(
                        width = 1.dp,
                        color = outline,
                        shape = favoriteShape,
                    )
                    .background(
                        panel,
                    )
                    .clickable(
                        onClick =
                            onToggleLike,
                    ),
            contentAlignment =
                Alignment.Center,
        ) {
            Icon(
                painter =
                    painterResource(
                        if (liked) {
                            R.drawable.favorite
                        } else {
                            R.drawable.favorite_border
                        },
                    ),
                contentDescription =
                    null,
                tint =
                    textColor,
                modifier =
                    Modifier.size(25.dp),
            )
        }
    }
}

@Composable
private fun CapsuleSideButton(
    iconRes: Int,
    enabled: Boolean,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .clickable(
                    enabled =
                        enabled,
                    onClick =
                        onClick,
                ),
        contentAlignment =
            Alignment.Center,
    ) {
        Icon(
            painter =
                painterResource(
                    iconRes,
                ),
            contentDescription =
                null,
            tint =
                textColor.copy(
                    alpha =
                        if (enabled) {
                            0.92f
                        } else {
                            0.25f
                        },
                ),
            modifier =
                Modifier.size(35.dp),
        )
    }
}

@Composable
private fun CapsuleAuxButton(
    iconRes: Int,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .clickable(
                    onClick =
                        onClick,
                ),
        contentAlignment =
            Alignment.Center,
    ) {
        Icon(
            painter =
                painterResource(
                    iconRes,
                ),
            contentDescription =
                null,
            tint =
                tint,
            modifier =
                Modifier.size(25.dp),
        )
    }
}

/**
 * Capsule orbit/comet Play/Pause button.
 *
 * Animatable survives Play/Pause state changes.
 *
 * When playback pauses, LaunchedEffect is cancelled and Animatable
 * retains its exact current angle.
 *
 * When playback resumes, the animation continues from that angle
 * instead of jumping back to zero.
 */
@Composable
private fun CapsuleOrbitButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    color: Color,
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

                /*
                 * Keep the numeric value small without
                 * resetting the visible orbit position.
                 */
                rotation.snapTo(
                    rotation.value % 360f,
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
                "CapsuleOrbitPauseAlpha",
        )

    Box(
        modifier =
            Modifier
                .size(82.dp)
                .clip(
                    CircleShape,
                )
                .background(
                    if (isPlaying) {
                        Color.Transparent
                    } else {
                        Color.Gray.copy(
                            alpha = 0.055f,
                        )
                    },
                )
                .clickable(
                    onClick =
                        onClick,
                ),
        contentAlignment =
            Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color =
                    color.copy(
                        alpha = 0.82f,
                    ),
                strokeWidth =
                    2.dp,
                modifier =
                    Modifier.size(32.dp),
            )
        } else {
            Canvas(
                modifier =
                    Modifier.size(68.dp),
            ) {
                val width =
                    size.width

                val height =
                    size.height

                val cx =
                    width / 2f

                val cy =
                    height / 2f

                val radius =
                    (min(width, height) / 2f) -
                        3.dp.toPx()

                /*
                 * Orbit ring.
                 */
                drawCircle(
                    color =
                        color.copy(
                            alpha = 0.16f * alpha,
                        ),
                    radius = radius,
                    center =
                        Offset(cx, cy),
                    style =
                        Stroke(
                            width = 1.dp.toPx(),
                        ),
                )

                /*
                 * Comet head plus fading trail.
                 */
                val trailCount = 8

                repeat(trailCount) { index ->
                    val angleRad =
                        Math.toRadians(
                            (
                                rotation.value -
                                    index * 6f
                            ).toDouble(),
                        )

                    val fade =
                        1f -
                            index /
                            trailCount.toFloat()

                    drawCircle(
                        color =
                            color.copy(
                                alpha =
                                    alpha * fade * 0.9f,
                            ),
                        radius =
                            (3.5f * fade)
                                .dp
                                .toPx()
                                .coerceAtLeast(1f),
                        center =
                            Offset(
                                x =
                                    cx +
                                        radius *
                                        cos(angleRad)
                                            .toFloat(),
                                y =
                                    cy +
                                        radius *
                                        sin(angleRad)
                                            .toFloat(),
                            ),
                    )
                }
            }

            Icon(
                painter =
                    painterResource(
                        if (isPlaying) {
                            R.drawable.pause
                        } else {
                            R.drawable.play
                        },
                    ),
                contentDescription =
                    null,
                tint =
                    color.copy(
                        alpha = alpha,
                    ),
                modifier =
                    Modifier.size(30.dp),
            )
        }
    }
}
