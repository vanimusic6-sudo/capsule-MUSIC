/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.playback.PlayerConnection
import com.nikhil.yt.utils.makeTimeString
import kotlinx.coroutines.delay

/** How long the controls stay up after the last touch before the artwork has the screen to itself. */
private const val ImmersiveChromeIdleMs = 4_000L

/** Long enough to read as a deliberate retreat rather than a glitch, short enough not to be waited on. */
private const val ImmersiveChromeFadeMs = 420

/** Where the scrim starts, as a fraction of height. Above this the artwork is untouched. */
private const val ImmersiveScrimStart = 0.42f

/**
 * Whether the controls should start counting down to their retreat.
 *
 * Every term earns its place. Off screen or behind the collapsed sheet, nothing can be seen, so a
 * countdown would be a timer running for nobody. Paused, the screen is being looked at rather than
 * listened to, and taking the controls away from someone reaching for them is the opposite of the
 * point. Already retreated, there is nothing left to hide, and re-arming would keep waking the
 * composition to decide that again.
 */
internal fun immersiveChromeShouldRetreat(
    visible: Boolean,
    isPlaying: Boolean,
    chromeShown: Boolean,
): Boolean = visible && isPlaying && chromeShown

/**
 * A player that gets out of the way.
 *
 * The other two designs are arrangements of controls with the artwork as one element among them.
 * This one inverts that: the artwork is the screen, edge to edge, and everything else is a guest
 * that leaves. Four seconds after the last touch, while something is playing, the controls fade
 * out and the cover is alone. A tap anywhere brings them back.
 *
 * Three rules the rest of this app is held to, and how they are met here:
 *
 * Nothing animates that cannot be seen. The idle countdown is armed only while the player is open,
 * on screen, and playing, and it is one delay that finishes — not a loop, not a ticker, not a
 * clock. Pausing cancels it and brings the controls back, because a paused player is one being
 * looked at rather than listened to.
 *
 * Nothing is hidden that cannot be recovered. The tap target is the whole screen and it is always
 * live, so the controls are never more than one touch away, and the fade is a layer alpha rather
 * than a removal, so nothing is re-laid-out when they return.
 *
 * Nothing here is sized by measurement. Every dimension is a constant, so there is no width or
 * radius that a future window size could turn negative.
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
    liked: Boolean,
    playerConnection: PlayerConnection,
    onToggleLike: () -> Unit,
    onArtistSelected: (MediaMetadata.Artist) -> Unit,
    onShowLyrics: () -> Unit,
    bottomPadding: Dp,
    open: Boolean = true,
) {
    val onScreen = appIsOnScreen()
    val visible = open && onScreen

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()

    val isLoading = playbackState == Player.STATE_BUFFERING

    var chromeShown by remember { mutableStateOf(true) }

    /*
     * Bumped on every touch. Keying the countdown on it restarts the wait without the effect having
     * to observe the touch itself, which would recompose this whole subtree on each tap.
     */
    var touchTick by remember { mutableIntStateOf(0) }

    /*
     * One delay that ends, armed only when there is something to retreat from. Not armed while
     * paused, closed or off screen — so an idle player behind another screen runs nothing at all.
     */
    LaunchedEffect(touchTick, isPlaying, visible, chromeShown) {
        if (!immersiveChromeShouldRetreat(visible, isPlaying, chromeShown)) return@LaunchedEffect
        delay(ImmersiveChromeIdleMs)
        chromeShown = false
    }

    // A pause is someone looking at the screen; give them the controls back unasked.
    LaunchedEffect(isPlaying) {
        if (!isPlaying) chromeShown = true
    }

    val chromeAlpha by animateFloatAsState(
        targetValue = if (chromeShown) 1f else 0f,
        animationSpec = tween(durationMillis = ImmersiveChromeFadeMs),
        label = "immersive-chrome",
    )

    val safeDuration = durationMs.coerceAtLeast(0L)
    val shownPosition = (sliderPosition ?: positionMs).coerceIn(0L, safeDuration)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    // Always live, so what was hidden is never more than one touch away.
                    if (chromeShown) touchTick += 1 else chromeShown = true
                },
    ) {
        AsyncImage(
            model = mediaMetadata.thumbnailUrl?.toHighResThumbnail(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        /*
         * The scrim is not part of the chrome and never fades: it is what keeps a pale cover from
         * washing out into the system bars, and it belongs to the artwork rather than the controls.
         */
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            ImmersiveScrimStart to Color.Black.copy(alpha = 0.30f),
                            1f to Color.Black.copy(alpha = 0.88f),
                        ),
                    ),
        )

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = chromeAlpha }
                    .padding(horizontal = 28.dp)
                    .padding(bottom = bottomPadding + 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = mediaMetadata.title,
                color = textColor,
                fontSize = 30.sp,
                lineHeight = 35.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = mediaMetadata.artists.joinToString { it.name },
                color = textColor.copy(alpha = 0.62f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        mediaMetadata.artists.firstOrNull { it.id != null }?.let(onArtistSelected)
                    },
            )

            Spacer(Modifier.height(26.dp))

            CapsuleThinSlider(
                value = shownPosition.toFloat(),
                valueRange = 0f..safeDuration.coerceAtLeast(1L).toFloat(),
                enabled = safeDuration > 0L,
                activeColor = textColor,
                inactiveColor = textColor.copy(alpha = 0.22f),
                onValueChange = {
                    touchTick += 1
                    onSeekPreview(it.toLong())
                },
                onValueChangeFinished = onSeekFinished,
                modifier = Modifier.fillMaxWidth().height(24.dp),
                trackHeight = 3.dp,
                thumbRadius = 5.dp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = makeTimeString(shownPosition),
                    color = textColor.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
                Text(
                    text = makeTimeString(safeDuration),
                    color = textColor.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ImmersiveIcon(
                    icon = if (liked) R.drawable.favorite else R.drawable.favorite_border,
                    tint = textColor.copy(alpha = if (liked) 1f else 0.62f),
                    enabled = true,
                    description = stringResource(
                        if (liked) R.string.action_remove_like else R.string.action_like,
                    ),
                ) {
                    touchTick += 1
                    onToggleLike()
                }

                ImmersiveIcon(
                    icon = R.drawable.skip_previous,
                    tint = textColor,
                    enabled = canSkipPrevious,
                    description = null,
                ) {
                    touchTick += 1
                    playerConnection.player.seekToPrevious()
                }

                Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    CapsuleOrbitButton(
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        // The comet turns only while it is on a screen someone is looking at.
                        visible = visible && chromeShown,
                        color = textColor,
                    ) {
                        touchTick += 1
                        playerConnection.player.togglePlayPause()
                    }
                }

                ImmersiveIcon(
                    icon = R.drawable.skip_next,
                    tint = textColor,
                    enabled = canSkipNext,
                    description = null,
                ) {
                    touchTick += 1
                    playerConnection.player.seekToNext()
                }

                /*
                 * The other designs open lyrics by tapping the artwork. Here the artwork is the
                 * whole screen and that tap already means "bring the controls back", so lyrics
                 * need a button of their own rather than a gesture competing with the one thing
                 * this screen must never lose.
                 */
                ImmersiveIcon(
                    icon = R.drawable.lyrics,
                    tint = textColor.copy(alpha = 0.62f),
                    enabled = true,
                    description = stringResource(R.string.lyrics),
                ) {
                    touchTick += 1
                    onShowLyrics()
                }
            }
        }
    }
}

@Composable
private fun ImmersiveIcon(
    icon: Int,
    tint: Color,
    enabled: Boolean,
    description: String?,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).clip(CircleShape),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = 0.28f),
            modifier = Modifier.size(28.dp),
        )
    }
}
