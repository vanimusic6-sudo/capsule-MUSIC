/**
 * Capsule MUSIC
 *
 * Capsule full-player skin ported from the original Capsule repository.
 * Visual structure, dimensions and gestures are kept from the donor.
 * Playback is still entirely owned by Velune PlayerConnection.
 *
 * GPL-3.0
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import com.nikhil.yt.constants.CropThumbnailToSquareKey
import com.nikhil.yt.constants.HidePlayerThumbnailKey
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.extensions.toggleRepeatMode
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.playback.PlayerConnection
import com.nikhil.yt.together.TogetherRole
import com.nikhil.yt.together.TogetherSessionState
import com.nikhil.yt.utils.makeTimeString
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

private val CapsuleArtworkShape =
    RoundedCornerShape(24.dp)

private val CapsuleControlsShape =
    RoundedCornerShape(24.dp)

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
    onArtworkClick: () -> Unit,
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

    val togetherState by
        playerConnection.service.togetherSessionState.collectAsState()

    val isListenTogetherGuest =
        (togetherState as? TogetherSessionState.Joined)
            ?.role is TogetherRole.Guest

    val hideArtwork by
        rememberPreference(
            HidePlayerThumbnailKey,
            defaultValue = false,
        )

    val cropAlbumArt by
        rememberPreference(
            CropThumbnailToSquareKey,
            defaultValue = false,
        )

    val isLoading =
        playbackState ==
            Player.STATE_BUFFERING

    val canSeek =
        !isListenTogetherGuest &&
            playerConnection.player
                .isCommandAvailable(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                )

    val secondaryText =
        textColor.copy(
            alpha = 0.55f,
        )

    val outline =
        textColor.copy(
            alpha = 0.16f,
        )

    val panel =
        textColor.copy(
            alpha = 0.025f,
        )

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
        (
            safeDuration -
                displayPosition
        ).coerceAtLeast(0L)

    val density =
        LocalDensity.current

    val swipeThresholdPx =
        with(density) {
            64.dp.toPx()
        }

    var localSliderPosition by
        remember(
            sliderPosition,
        ) {
            mutableStateOf<Long?>(
                sliderPosition,
            )
        }

    var showSleepTimerDialog by
        remember {
            mutableStateOf(false)
        }

    var sleepTimerValue by
        remember {
            mutableFloatStateOf(30f)
        }

    val sleepTimerEnabled =
        remember(
            playerConnection.service
                .sleepTimer
                .triggerTime,
            playerConnection.service
                .sleepTimer
                .pauseWhenSongEnd,
        ) {
            playerConnection.service
                .sleepTimer
                .isActive
        }

    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = {
                showSleepTimerDialog =
                    false
            },
            title = {
                Text("Sleep timer")
            },
            text = {
                Column {
                    Text(
                        "${sleepTimerValue.toInt()} min",
                    )

                    Slider(
                        value =
                            sleepTimerValue,
                        onValueChange = {
                            sleepTimerValue =
                                it
                        },
                        valueRange =
                            5f..120f,
                        steps = 22,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playerConnection
                            .service
                            .sleepTimer
                            .start(
                                sleepTimerValue
                                    .toInt(),
                            )

                        showSleepTimerDialog =
                            false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSleepTimerDialog =
                            false
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

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
                    bottom =
                        bottomPadding,
                )
                /*
                 * Original Capsule gesture:
                 * swipe DOWN by 64 dp anywhere on the player to open Queue.
                 */
                .pointerInput(
                    onExpandQueue,
                    swipeThresholdPx,
                ) {
                    var accumulated =
                        0f

                    var opened =
                        false

                    detectVerticalDragGestures(
                        onDragStart = {
                            accumulated =
                                0f

                            opened =
                                false
                        },
                        onVerticalDrag = {
                                change,
                                dragAmount,
                            ->
                            if (
                                dragAmount >
                                0f
                            ) {
                                accumulated +=
                                    dragAmount
                            } else {
                                accumulated =
                                    (
                                        accumulated +
                                            dragAmount
                                    ).coerceAtLeast(
                                        0f,
                                    )
                            }

                            if (
                                !opened &&
                                accumulated >=
                                swipeThresholdPx
                            ) {
                                change.consume()

                                opened =
                                    true

                                onExpandQueue()
                            }
                        },
                        onDragEnd = {
                            accumulated =
                                0f

                            opened =
                                false
                        },
                        onDragCancel = {
                            accumulated =
                                0f

                            opened =
                                false
                        },
                    )
                },
        horizontalAlignment =
            Alignment.CenterHorizontally,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 18.dp,
                        end = 18.dp,
                        top = 10.dp,
                        bottom = 14.dp,
                    ),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            Text(
                text = "NOW PLAYING",
                color =
                    secondaryText,
                fontFamily =
                    FontFamily.Monospace,
                fontSize = 17.sp,
                fontWeight =
                    FontWeight.SemiBold,
            )

            Text(
                text =
                    if (
                        safeDuration >
                        0L
                    ) {
                        makeTimeString(
                            safeDuration,
                        )
                    } else {
                        ""
                    },
                color =
                    secondaryText,
                fontFamily =
                    FontFamily.Monospace,
                fontSize = 17.sp,
            )
        }

        /*
         * Exact donor geometry:
         * same cover size, only visually lifted by -8 dp.
         */
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        horizontal =
                            18.dp,
                        vertical =
                            6.dp,
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
                            1.dp,
                            outline,
                            CapsuleArtworkShape,
                        )
                        .background(
                            textColor.copy(
                                alpha =
                                    0.045f,
                            ),
                        )
                        .clickable(
                            onClick =
                                onArtworkClick,
                        ),
                contentAlignment =
                    Alignment.Center,
            ) {
                if (hideArtwork) {
                    Icon(
                        painter =
                            painterResource(
                                R.drawable.album,
                            ),
                        contentDescription =
                            mediaMetadata.title,
                        tint =
                            secondaryText,
                        modifier =
                            Modifier.size(
                                72.dp,
                            ),
                    )
                } else {
                    AsyncImage(
                        model =
                            mediaMetadata
                                .thumbnailUrl,
                        contentDescription =
                            mediaMetadata.title,
                        contentScale =
                            if (cropAlbumArt) {
                                ContentScale.Crop
                            } else {
                                ContentScale.Fit
                            },
                        modifier =
                            Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal =
                            18.dp,
                    ),
        ) {
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
                            .clipToBounds()
                            .padding(
                                end = 10.dp,
                            ),
                ) {
                    Text(
                        text =
                            mediaMetadata.title,
                        color =
                            textColor,
                        fontSize =
                            28.sp,
                        lineHeight =
                            31.sp,
                        fontWeight =
                            FontWeight.Bold,
                        maxLines = 1,
                        overflow =
                            TextOverflow.Ellipsis,
                        modifier =
                            Modifier.fillMaxWidth(),
                    )

                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically,
                    ) {
                        if (
                            mediaMetadata.explicit
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(
                                            width =
                                                15.dp,
                                            height =
                                                15.dp,
                                        )
                                        .clip(
                                            RoundedCornerShape(
                                                2.dp,
                                            ),
                                        )
                                        .border(
                                            1.dp,
                                            secondaryText,
                                            RoundedCornerShape(
                                                2.dp,
                                            ),
                                        ),
                                contentAlignment =
                                    Alignment.Center,
                            ) {
                                Text(
                                    text = "E",
                                    color =
                                        secondaryText,
                                    fontSize =
                                        9.sp,
                                    lineHeight =
                                        9.sp,
                                    fontWeight =
                                        FontWeight.Bold,
                                )
                            }

                            Spacer(
                                Modifier.width(
                                    5.dp,
                                ),
                            )
                        }

                        Text(
                            text =
                                mediaMetadata
                                    .artists
                                    .joinToString {
                                        it.name
                                    },
                            color =
                                secondaryText,
                            fontSize =
                                17.sp,
                            lineHeight =
                                21.sp,
                            maxLines = 1,
                            overflow =
                                TextOverflow.Ellipsis,
                            modifier =
                                Modifier.weight(
                                    1f,
                                    fill = false,
                                ),
                        )
                    }
                }

                CapsuleShareFavoriteButtons(
                    textColor =
                        textColor,
                    outlineColor =
                        outline,
                    panelColor =
                        panel,
                    liked =
                        liked,
                    mediaId =
                        mediaMetadata.id,
                    onToggleLike =
                        onToggleLike,
                    context =
                        context,
                )
            }

            Spacer(
                Modifier.height(
                    14.dp,
                ),
            )

            Slider(
                value =
                    (
                        localSliderPosition
                            ?: displayPosition
                    )
                        .toFloat()
                        .coerceIn(
                            0f,
                            safeDuration
                                .coerceAtLeast(
                                    1L,
                                )
                                .toFloat(),
                        ),
                valueRange =
                    0f..
                        safeDuration
                            .coerceAtLeast(
                                1L,
                            )
                            .toFloat(),
                onValueChange = {
                    localSliderPosition =
                        it.toLong()
                },
                onValueChangeFinished = {
                    localSliderPosition
                        ?.let {
                            playerConnection
                                .player
                                .seekTo(it)
                        }

                    localSliderPosition =
                        null
                },
                enabled =
                    canSeek &&
                        safeDuration >
                        0L,
                thumb = {
                    Spacer(
                        Modifier.size(
                            0.dp,
                        ),
                    )
                },
                colors =
                    SliderDefaults.colors(
                        activeTrackColor =
                            textColor.copy(
                                alpha =
                                    0.92f,
                            ),
                        inactiveTrackColor =
                            textColor.copy(
                                alpha =
                                    0.22f,
                            ),
                        disabledActiveTrackColor =
                            textColor.copy(
                                alpha =
                                    0.36f,
                            ),
                        disabledInactiveTrackColor =
                            textColor.copy(
                                alpha =
                                    0.12f,
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
                            horizontal =
                                2.dp,
                        ),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
            ) {
                Text(
                    text =
                        makeTimeString(
                            localSliderPosition
                                ?: displayPosition,
                        ),
                    color =
                        secondaryText,
                    fontFamily =
                        FontFamily.Monospace,
                    fontSize =
                        13.sp,
                )

                Text(
                    text =
                        if (
                            safeDuration >
                            0L
                        ) {
                            "-${
                                makeTimeString(
                                    remaining,
                                )
                            }"
                        } else {
                            ""
                        },
                    color =
                        secondaryText,
                    fontFamily =
                        FontFamily.Monospace,
                    fontSize =
                        13.sp,
                )
            }

            Spacer(
                Modifier.height(
                    16.dp,
                ),
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(
                            CapsuleControlsShape,
                        )
                        .border(
                            1.dp,
                            outline,
                            CapsuleControlsShape,
                        )
                        .background(
                            panel,
                        ),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(
                                92.dp,
                            ),
                    verticalAlignment =
                        Alignment.CenterVertically,
                ) {
                    CapsuleTransportSideButton(
                        iconRes =
                            R.drawable.skip_previous,
                        enabled =
                            canSkipPrevious &&
                                !isListenTogetherGuest,
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
                                if (!isListenTogetherGuest) {
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
                                }
                            },
                        )
                    }

                    CapsuleTransportSideButton(
                        iconRes =
                            R.drawable.skip_next,
                        enabled =
                            canSkipNext &&
                                !isListenTogetherGuest,
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
                            .height(
                                1.dp,
                            )
                            .background(
                                outline,
                            ),
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(
                                66.dp,
                            ),
                    verticalAlignment =
                        Alignment.CenterVertically,
                ) {
                    CapsuleAuxButton(
                        iconRes =
                            R.drawable.bedtime,
                        tint =
                            if (
                                sleepTimerEnabled
                            ) {
                                textColor
                            } else {
                                textColor.copy(
                                    alpha =
                                        0.82f,
                                )
                            },
                        enabled =
                            !isListenTogetherGuest,
                        onClick = {
                            showSleepTimerDialog =
                                true
                        },
                        modifier =
                            Modifier.weight(1f),
                    )

                    CapsuleAuxButton(
                        iconRes =
                            when (
                                repeatMode
                            ) {
                                Player.REPEAT_MODE_ONE ->
                                    R.drawable.repeat_one

                                else ->
                                    R.drawable.repeat
                            },
                        tint =
                            if (
                                repeatMode ==
                                Player.REPEAT_MODE_OFF ||
                                isListenTogetherGuest
                            ) {
                                textColor.copy(
                                    alpha =
                                        0.46f,
                                )
                            } else {
                                textColor.copy(
                                    alpha =
                                        0.88f,
                                )
                            },
                        enabled =
                            !isListenTogetherGuest,
                        onClick = {
                            playerConnection.player
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
                                alpha =
                                    0.88f,
                            ),
                        enabled =
                            true,
                        onClick =
                            onMenuClick,
                        modifier =
                            Modifier.weight(1f),
                    )
                }
            }

            Spacer(
                Modifier.height(
                    14.dp,
                ),
            )

            Box(
                modifier =
                    Modifier
                        .align(
                            Alignment.CenterHorizontally,
                        )
                        .width(
                            44.dp,
                        )
                        .height(
                            4.dp,
                        )
                        .clip(
                            CircleShape,
                        )
                        .background(
                            textColor.copy(
                                alpha =
                                    0.22f,
                            ),
                        )
                        .clickable(
                            onClick =
                                onExpandQueue,
                        ),
            )

            Spacer(
                Modifier.height(
                    12.dp,
                ),
            )
        }
    }
}

@Composable
private fun CapsuleShareFavoriteButtons(
    textColor: Color,
    outlineColor: Color,
    panelColor: Color,
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
            Arrangement.spacedBy(
                3.dp,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .size(
                        52.dp,
                    )
                    .clip(
                        shareShape,
                    )
                    .border(
                        1.dp,
                        outlineColor,
                        shareShape,
                    )
                    .background(
                        panelColor,
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
                    Modifier.size(
                        24.dp,
                    ),
            )
        }

        Box(
            modifier =
                Modifier
                    .size(
                        52.dp,
                    )
                    .clip(
                        favoriteShape,
                    )
                    .border(
                        1.dp,
                        outlineColor,
                        favoriteShape,
                    )
                    .background(
                        panelColor,
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
                    Modifier.size(
                        25.dp,
                    ),
            )
        }
    }
}

@Composable
private fun CapsuleTransportSideButton(
    iconRes: Int,
    enabled: Boolean,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(
                    92.dp,
                )
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
                Modifier.size(
                    35.dp,
                ),
        )
    }
}

@Composable
private fun CapsuleAuxButton(
    iconRes: Int,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(
                    66.dp,
                )
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
                if (enabled) {
                    tint
                } else {
                    tint.copy(
                        alpha =
                            0.38f,
                    )
                },
            modifier =
                Modifier.size(
                    25.dp,
                ),
        )
    }
}

/**
 * Donor Capsule comet button.
 *
 * Animatable deliberately survives Play/Pause changes.
 * Pause freezes the dot at the exact current angle;
 * Resume continues from the same angle.
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
                "CapsuleOrbitPauseAlpha",
        )

    Box(
        modifier =
            Modifier
                .size(
                    82.dp,
                )
                .clip(
                    CircleShape,
                )
                .background(
                    if (isPlaying) {
                        Color.Transparent
                    } else {
                        Color.Gray.copy(
                            alpha =
                                0.055f,
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
                        alpha =
                            0.82f,
                    ),
                strokeWidth =
                    2.dp,
                modifier =
                    Modifier.size(
                        32.dp,
                    ),
            )
        } else {
            Canvas(
                Modifier.size(
                    68.dp,
                ),
            ) {
                val w =
                    size.width

                val h =
                    size.height

                val cx =
                    w / 2f

                val cy =
                    h / 2f

                val rx =
                    w * 0.47f

                val ry =
                    h * 0.19f

                val orbitColor =
                    color.copy(
                        alpha =
                            alpha,
                    )

                rotate(
                    -25f,
                ) {
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
                                    w *
                                        0.045f,
                            ),
                    )

                    val angle =
                        Math.toRadians(
                            rotation.value
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
                        start =
                            tail,
                        end =
                            point,
                        strokeWidth =
                            w *
                                0.035f,
                    )

                    drawCircle(
                        color =
                            orbitColor,
                        radius =
                            w *
                                0.052f,
                        center =
                            point,
                    )
                }

                drawCircle(
                    color =
                        orbitColor,
                    radius =
                        w *
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
