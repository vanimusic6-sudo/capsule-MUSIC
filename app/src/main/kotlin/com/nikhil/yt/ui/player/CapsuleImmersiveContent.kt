/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.constants.PlayerBackgroundStyle
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.extensions.toggleRepeatMode
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.playback.PlayerConnection
import com.nikhil.yt.playback.video.CapsulePlaybackMode
import com.nikhil.yt.ui.component.CapsuleFavoriteColors
import com.nikhil.yt.ui.component.CapsuleFavoriteIcon
import com.nikhil.yt.utils.makeTimeString

/** How much of the sheet the cover takes before it starts to go. */
private const val ImmersiveArtworkFraction = 0.62f

/** Where inside the cover the dissolve begins. Above this the image is untouched. */
private const val ImmersiveFadeStart = 0.52f

/** Smallest cover that still reads as one; largest that still leaves the controls their room. */
private val ImmersiveArtworkMin = 220.dp
private val ImmersiveArtworkMax = 620.dp

/**
 * How tall the cover is in a sheet of this height.
 *
 * Clamped at both ends. A very short window must not be handed a cover that leaves no room for
 * the controls, and a very tall one must not be handed a cover that runs past the sheet — and
 * neither end may produce a height that a later layout pass could read as negative.
 */
internal fun immersiveArtworkHeight(available: Dp): Dp =
    (available * ImmersiveArtworkFraction).coerceIn(ImmersiveArtworkMin, ImmersiveArtworkMax)

/**
 * A player where the cover is the screen.
 *
 * Cosmo and Light both frame the artwork: a card with an edge, sitting in its own reserved space
 * with the controls arranged beneath. This one has no frame. The cover runs the full width from
 * the very top and simply stops being there — the bottom half of it dissolves into the player's
 * own background, so there is no line anywhere saying where the picture ends and the app begins.
 *
 * Everything below that is deliberately familiar. The transport panel is Capsule Light's, reused
 * rather than redrawn, so the two designs cannot drift apart. What differs is what sits above it:
 * the title keeps its own line with lyrics and like as round chips beside it, and the progress bar
 * is a full-width rail rather than a hairline.
 *
 * The dissolve is a gradient drawn over the image in the colour behind it, not a mask. A mask
 * would need an offscreen layer for the whole cover, and this app has already paid for one of
 * those once — the shimmer's alpha buffer was what made the phone warm. Painting the background's
 * own colour on top reaches the same picture with an ordinary draw.
 */
@Composable
fun CapsuleImmersiveContent(
    mediaMetadata: MediaMetadata,
    sliderPosition: Long?,
    positionMs: Long,
    durationMs: Long,
    onSeekPreview: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    textColor: Color,
    playerBackground: PlayerBackgroundStyle,
    gradientColors: List<Color>,
    liked: Boolean,
    playerConnection: PlayerConnection,
    onToggleLike: () -> Unit,
    onArtistSelected: (MediaMetadata.Artist) -> Unit,
    onShowLyrics: () -> Unit,
    onMenuClick: () -> Unit,
    bottomPadding: Dp,
    open: Boolean = true,
) {
    val onScreen = appIsOnScreen()
    val visible = open && onScreen

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val videoPlaybackState by playerConnection.service.videoPlaybackState.collectAsState()

    val isLoading = playbackState == Player.STATE_BUFFERING

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerValue by remember { mutableFloatStateOf(30f) }
    val sleepTimerEnabled =
        remember(
            playerConnection.service.sleepTimer.triggerTime,
            playerConnection.service.sleepTimer.pauseWhenSongEnd,
        ) { playerConnection.service.sleepTimer.isActive }

    if (showSleepTimerDialog) {
        CapsuleSleepTimerDialog(
            minutes = sleepTimerValue,
            enabled = true,
            onMinutesChange = { sleepTimerValue = it },
            onConfirm = {
                playerConnection.service.sleepTimer.start(sleepTimerValue.toInt())
                showSleepTimerDialog = false
            },
            onDismiss = { showSleepTimerDialog = false },
        )
    }

    val safeDuration = durationMs.coerceAtLeast(0L)
    val shownPosition = (sliderPosition ?: positionMs).coerceIn(0L, safeDuration)

    /*
     * What the cover dissolves into. The player paints its own background behind this content, so
     * the fade has to end on that exact colour or a seam appears where the gradient stops.
     */
    val fadeInto =
        when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.surface
            else -> gradientColors.lastOrNull() ?: Color.Black
        }

    val chipSurface = textColor.copy(alpha = 0.10f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val artworkHeight = immersiveArtworkHeight(maxHeight)

        Box(modifier = Modifier.fillMaxWidth().height(artworkHeight)) {
            AsyncImage(
                model = mediaMetadata.thumbnailUrl?.toHighResThumbnail(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                ImmersiveFadeStart to Color.Transparent,
                                0.80f to fadeInto.copy(alpha = 0.72f),
                                1f to fadeInto,
                            ),
                        ),
            )
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = bottomPadding + 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mediaMetadata.title,
                        color = textColor,
                        fontSize = 28.sp,
                        lineHeight = 33.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = mediaMetadata.artists.joinToString { it.name },
                        color = textColor.copy(alpha = 0.62f),
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                mediaMetadata.artists.firstOrNull { it.id != null }
                                    ?.let(onArtistSelected)
                            },
                    )
                }

                ImmersiveChip(background = chipSurface, onClick = onShowLyrics) {
                    Icon(
                        painter = painterResource(R.drawable.format_quote),
                        contentDescription = stringResource(R.string.lyrics),
                        tint = textColor,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Spacer(Modifier.size(10.dp))

                val favoriteInteraction = remember { MutableInteractionSource() }
                ImmersiveChip(
                    background = chipSurface,
                    interactionSource = favoriteInteraction,
                    onClick = onToggleLike,
                ) {
                    CapsuleFavoriteIcon(
                        liked = liked,
                        interactionSource = favoriteInteraction,
                        tint =
                            if (liked) {
                                CapsuleFavoriteColors.selected(textColor)
                            } else {
                                textColor
                            },
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            CapsuleThinSlider(
                value = shownPosition.toFloat(),
                valueRange = 0f..safeDuration.coerceAtLeast(1L).toFloat(),
                enabled = safeDuration > 0L,
                activeColor = textColor,
                inactiveColor = textColor.copy(alpha = 0.22f),
                onValueChange = { onSeekPreview(it.toLong()) },
                onValueChangeFinished = onSeekFinished,
                modifier = Modifier.fillMaxWidth().height(22.dp),
                trackHeight = 8.dp,
                thumbRadius = 5.dp,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(makeTimeString(shownPosition), color = textColor.copy(alpha = 0.55f), fontSize = 13.sp)
                Text(makeTimeString(safeDuration), color = textColor.copy(alpha = 0.55f), fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.shuffle),
                        stringResource(R.string.shuffle),
                        tint = textColor.copy(alpha = if (shuffleEnabled) 1f else 0.5f),
                        modifier = Modifier.size(21.dp),
                    )
                }

                CapsuleAudioVideoToggle(
                    lightStyle = true,
                    state = videoPlaybackState,
                    textColor = textColor,
                    enabled = true,
                    onAudioClick = {
                        playerConnection.service.setCapsulePlaybackMode(CapsulePlaybackMode.AUDIO)
                    },
                    onVideoClick = {
                        playerConnection.service.setCapsulePlaybackMode(CapsulePlaybackMode.VIDEO)
                    },
                    modifier = Modifier.weight(1f),
                )

                IconButton(
                    onClick = { showSleepTimerDialog = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.bedtime),
                        stringResource(R.string.sleep_timer),
                        tint = textColor.copy(alpha = if (sleepTimerEnabled) 1f else 0.5f),
                        modifier = Modifier.size(21.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Capsule Light's transport panel, reused rather than redrawn: one panel, one place to
            // change it, and no chance of the two designs drifting apart.
            CapsuleLightControls(
                textColor = textColor,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                enabled = true,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                onShuffle = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
                onPrevious = { playerConnection.player.seekToPrevious() },
                onNext = { playerConnection.player.seekToNext() },
                onRepeat = { playerConnection.player.toggleRepeatMode() },
                orbit = {
                    CapsuleOrbitButton(
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        visible = visible,
                        color = textColor,
                    ) { playerConnection.player.togglePlayPause() }
                },
                onMenuClick = onMenuClick,
            )
        }
    }
}

/** A round translucent seat for one icon, as the title row wears beside it. */
@Composable
private fun ImmersiveChip(
    background: Color,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
