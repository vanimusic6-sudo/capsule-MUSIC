/**
 * Capsule MUSIC
 *
 * Capsule Mini Player adapted from the original Capsule/Metrolist implementation.
 *
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import com.nikhil.yt.ui.component.CapsuleFavoriteIcon
import com.nikhil.yt.ui.component.CapsuleSubscribeIcon
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.nikhil.yt.ui.component.CapsuleFavoriteColors
import android.os.SystemClock
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.MiniPlayerHeight
import com.nikhil.yt.constants.MiniPlayerBackgroundStyle
import com.nikhil.yt.constants.MiniPlayerBackgroundStyleKey
import com.nikhil.yt.constants.SwipeSensitivityKey
import com.nikhil.yt.constants.SwipeThumbnailKey
import com.nikhil.yt.db.entities.ArtistEntity
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.together.TogetherRole
import com.nikhil.yt.together.TogetherSessionState
import com.nikhil.yt.ui.component.BottomSheetState
import com.nikhil.yt.ui.component.canStartMiniDismissGesture
import com.nikhil.yt.ui.screens.settings.DiscordPresenceManager
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * MainActivity provides whether Capsule Dock is really visible under Mini Player.
 *
 * This is what lets the lower corners become square only while both pieces
 * are physically joined into one capsule.
 */
val LocalCapsuleDockVisible =
    compositionLocalOf { false }

private val CapsuleMiniOutline =
    Color(0xFF363640)

private val CapsuleMiniPrimary =
    Color(0xFFF1F1F1)

private val CapsuleMiniText =
    Color(0xFFF4F4F4)

private val CapsuleMiniMuted =
    Color(0xFFAAAAAA)

private val CapsuleMiniError =
    Color(0xFFFF8A8A)

private enum class MiniDragAxis { HORIZONTAL, VERTICAL }

@Composable
fun CapsuleMiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
    standardStyle: Boolean = false,
    visible: Boolean = true,
    foregroundAlpha: () -> Float = { 1f },
    foregroundInteractive: Boolean = true,
    playerState: BottomSheetState? = null,
    onVerticalDismiss: (() -> Unit)? = null,
) {
    val playerConnection =
        LocalPlayerConnection.current ?: return

    val onScreen = appIsOnScreen()
    val visualsActive = visible && onScreen

    val mediaMetadata by
        playerConnection.mediaMetadata.collectAsState()

    val miniPlayerBackground by
        rememberEnumPreference(
            MiniPlayerBackgroundStyleKey,
            defaultValue = MiniPlayerBackgroundStyle.CAPSULE_STAR,
        )

    val miniArtworkColors =
        rememberCapsuleArtworkColors(
            mediaMetadata = mediaMetadata,
            // Palette extraction is event-driven and cached, not a frame clock. Keep it warm while
            // another player surface covers the mini-player so collapsing reveals the correct
            // colour immediately instead of painting after the animation has already started.
            enabled =
                onScreen &&
                    miniPlayerBackground !=
                    MiniPlayerBackgroundStyle.THEME,
        )

    if (!visualsActive) {
        // Preserve BottomSheet's collapsed measurement, but do not keep the hidden mini-player's
        // database/session/swipe subscriptions or animation objects alive underneath full-player.
        // Palette extraction above intentionally stays warm because the reveal color is perceptual.
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(MiniPlayerHeight),
        )
        return
    }

    val playbackState by
        playerConnection.playbackState.collectAsState()

    val isPlaying by
        playerConnection.isPlaying.collectAsState()

    LaunchedEffect(isPlaying, playbackState) {
        // The compact surface only owns the mathematical comet phase while it is actually visible.
        // The full player/lyrics renderer takes over when the compact surface leaves composition.
        CapsuleCometPhaseClock.setRunning(
            isPlaying && playbackState == Player.STATE_READY,
        )
    }

    val database =
        LocalDatabase.current

    val currentSong by
        playerConnection.currentSong.collectAsState(
            initial = null,
        )

    val capsuleDockVisible =
        LocalCapsuleDockVisible.current

    val swipeSensitivity by
        rememberPreference(
            SwipeSensitivityKey,
            defaultValue = 0.73f,
        )

    val swipeThumbnailPref by
        rememberPreference(
            SwipeThumbnailKey,
            defaultValue = true,
        )

    val togetherState by
        playerConnection.service.togetherSessionState.collectAsState()

    val isListenTogetherGuest =
        (togetherState as? TogetherSessionState.Joined)
            ?.role is TogetherRole.Guest

    val swipeThumbnail =
        swipeThumbnailPref &&
            !isListenTogetherGuest &&
            foregroundInteractive

    // Do not key the pointer coroutine to this value: foreground alpha/input changes while a
    // vertical open gesture is already in flight. Restarting pointerInput there cancels the exact
    // gesture that is moving the sheet.
    val currentSwipeThumbnail by rememberUpdatedState(swipeThumbnail)

    val layoutDirection =
        LocalLayoutDirection.current

    val coroutineScope =
        rememberCoroutineScope()

    val offsetXAnimatable =
        remember {
            Animatable(0f)
        }

    var dragStartTime by
        remember {
            mutableLongStateOf(0L)
        }

    var totalDragDistance by
        remember {
            mutableFloatStateOf(0f)
        }

    val animationSpec = MiniPlayerSwipeSpring

    val density = LocalDensity.current
    val normalizedSwipeSensitivity = swipeSensitivity.coerceIn(0f, 1f)
    val swipeDistanceThresholdPx =
        remember(density, normalizedSwipeSensitivity) {
            with(density) {
                (84.dp - 36.dp * normalizedSwipeSensitivity).toPx()
            }
        }
    val fastSwipeMinDistancePx =
        remember(density) {
            with(density) { 24.dp.toPx() }
        }
    val swipeVelocityThresholdPxPerMs =
        remember(density, normalizedSwipeSensitivity) {
            with(density) {
                (0.55.dp - 0.30.dp * normalizedSwipeSensitivity).toPx()
            }
        }

    fun restartPresence() {
        if (DiscordPresenceManager.isRunning()) {
            runCatching {
                DiscordPresenceManager.restart()
            }
        }
    }

    fun seekPreviousPreservingPlayback() {
        if (isListenTogetherGuest) return

        val wasPlayWhenReady =
            playerConnection.player.playWhenReady

        playerConnection.player.seekToPreviousMediaItem()

        if (
            playerConnection.player.playbackState ==
            Player.STATE_IDLE ||
            playerConnection.player.playbackState ==
            Player.STATE_ENDED
        ) {
            playerConnection.player.prepare()
        }

        playerConnection.player.playWhenReady =
            wasPlayWhenReady

        restartPresence()
    }

    fun seekNextPreservingPlayback() {
        if (isListenTogetherGuest) return

        val wasPlayWhenReady =
            playerConnection.player.playWhenReady

        playerConnection.player.seekToNextMediaItem()

        if (
            playerConnection.player.playbackState ==
            Player.STATE_IDLE ||
            playerConnection.player.playbackState ==
            Player.STATE_ENDED
        ) {
            playerConnection.player.prepare()
        }

        playerConnection.player.playWhenReady =
            wasPlayWhenReady

        restartPresence()
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MiniPlayerHeight)
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = if (standardStyle) 12.dp else 10.dp)
                .let { baseModifier ->
                    if (visualsActive && playerState != null) {
                        baseModifier.pointerInput(
                            mediaMetadata?.id,
                            swipeDistanceThresholdPx,
                            swipeVelocityThresholdPxPerMs,
                            layoutDirection,
                            playerState,
                            onVerticalDismiss,
                        ) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!canStartMiniDismissGesture(playerState.rawProgress)) {
                                    // Expanded/transitioning gestures belong to BottomSheet.
                                    // This compact coordinator must remain a passive observer.
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.none { it.pressed }) break
                                    }
                                    return@awaitEachGesture
                                }
                                var axis: MiniDragAxis? = null
                                var dragTargetOffset = offsetXAnimatable.value
                                var motionJob: Job? = null
                                val verticalVelocity = VelocityTracker()

                                val slopChange =
                                    awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                                        if (change.isConsumed) return@awaitTouchSlopOrCancellation

                                        val horizontal = kotlin.math.abs(overSlop.x)
                                        val vertical = kotlin.math.abs(overSlop.y)
                                        val candidate =
                                            if (horizontal > vertical) {
                                                MiniDragAxis.HORIZONTAL
                                            } else {
                                                MiniDragAxis.VERTICAL
                                            }

                                        if (candidate == MiniDragAxis.HORIZONTAL && !currentSwipeThumbnail) {
                                            return@awaitTouchSlopOrCancellation
                                        }

                                        axis = candidate
                                        change.consume()

                                        when (candidate) {
                                            MiniDragAxis.HORIZONTAL -> {
                                                motionJob?.cancel()
                                                dragTargetOffset = offsetXAnimatable.value
                                                motionJob =
                                                    coroutineScope.launch {
                                                        offsetXAnimatable.stop()
                                                    }
                                                dragStartTime = SystemClock.uptimeMillis()
                                                totalDragDistance = 0f
                                            }

                                            MiniDragAxis.VERTICAL -> {
                                                verticalVelocity.resetTracking()
                                                verticalVelocity.addPointerInputChange(change)
                                                playerState.dispatchRawDelta(overSlop.y)
                                            }
                                        }
                                    }

                                if (slopChange == null || axis == null) {
                                    return@awaitEachGesture
                                }

                                val completed =
                                    drag(slopChange.id) { change ->
                                        if (change.isConsumed) return@drag
                                        val delta = change.positionChange()

                                        when (axis) {
                                            MiniDragAxis.HORIZONTAL -> {
                                                val adjustedDragAmount =
                                                    if (layoutDirection == LayoutDirection.Rtl) {
                                                        -delta.x
                                                    } else {
                                                        delta.x
                                                    }
                                                val tryingToSwipeRight = adjustedDragAmount > 0f
                                                val tryingToSwipeLeft = adjustedDragAmount < 0f
                                                val canSkipPrevious =
                                                    playerConnection.player.previousMediaItemIndex != -1
                                                val canSkipNext =
                                                    playerConnection.player.nextMediaItemIndex != -1
                                                val allowLeft = tryingToSwipeLeft && canSkipNext
                                                val allowRight = tryingToSwipeRight && canSkipPrevious
                                                val canReturnToCenter =
                                                    (tryingToSwipeRight &&
                                                        !canSkipPrevious &&
                                                        dragTargetOffset < 0f) ||
                                                        (tryingToSwipeLeft &&
                                                            !canSkipNext &&
                                                            dragTargetOffset > 0f)

                                                if (allowLeft || allowRight || canReturnToCenter) {
                                                    change.consume()
                                                    totalDragDistance +=
                                                        kotlin.math.abs(adjustedDragAmount)
                                                    dragTargetOffset += adjustedDragAmount
                                                    val nextOffset = dragTargetOffset
                                                    motionJob?.cancel()
                                                    motionJob =
                                                        coroutineScope.launch {
                                                            offsetXAnimatable.snapTo(nextOffset)
                                                        }
                                                }
                                            }

                                            MiniDragAxis.VERTICAL -> {
                                                change.consume()
                                                verticalVelocity.addPointerInputChange(change)
                                                playerState.dispatchRawDelta(delta.y)
                                            }

                                            null -> Unit
                                        }
                                    }

                                when (axis) {
                                    MiniDragAxis.HORIZONTAL -> {
                                        val dragDuration =
                                            SystemClock.uptimeMillis() - dragStartTime
                                        val velocity =
                                            if (dragDuration > 0L) {
                                                totalDragDistance / dragDuration
                                            } else {
                                                0f
                                            }
                                        val currentOffset = dragTargetOffset
                                        val shouldChangeSong =
                                            completed &&
                                                (
                                                    (
                                                        kotlin.math.abs(currentOffset) >
                                                            fastSwipeMinDistancePx &&
                                                            velocity >
                                                            swipeVelocityThresholdPxPerMs
                                                    ) ||
                                                        kotlin.math.abs(currentOffset) >
                                                            swipeDistanceThresholdPx
                                                )

                                        if (shouldChangeSong) {
                                            val canSkipPrevious =
                                                playerConnection.player.previousMediaItemIndex != -1
                                            val canSkipNext =
                                                playerConnection.player.nextMediaItemIndex != -1
                                            if (currentOffset > 0f && canSkipPrevious) {
                                                seekPreviousPreservingPlayback()
                                            } else if (currentOffset <= 0f && canSkipNext) {
                                                seekNextPreservingPlayback()
                                            }
                                        }

                                        motionJob?.cancel()
                                        motionJob =
                                            coroutineScope.launch {
                                                offsetXAnimatable.snapTo(currentOffset)
                                                offsetXAnimatable.animateTo(0f, animationSpec)
                                            }
                                    }

                                    MiniDragAxis.VERTICAL -> {
                                        val velocity =
                                            if (completed) {
                                                -verticalVelocity.calculateVelocity().y
                                            } else {
                                                0f
                                            }
                                        verticalVelocity.resetTracking()
                                        if (completed) {
                                            playerState.performFling(
                                                velocity = velocity,
                                                onDismiss = onVerticalDismiss,
                                            )
                                        } else {
                                            // Cancellation is never interpreted as a destructive
                                            // dismiss. Return to the compact anchor instead.
                                            playerState.collapseSoft()
                                        }
                                    }

                                    null -> Unit
                                }
                            }
                        }
                    } else {
                        baseModifier
                    }
                },
    ) {
        val capsuleBottomRadius by
            animateDpAsState(
                targetValue =
                    if (capsuleDockVisible && !standardStyle) {
                        0.dp
                    } else {
                        24.dp
                    },
                animationSpec =
                    spring(
                        dampingRatio =
                            Spring.DampingRatioNoBouncy,
                        stiffness =
                            Spring.StiffnessMediumLow,
                    ),
                label =
                    "capsuleMiniPlayerBottomRadius",
            )

        val miniPlayerShape = if (standardStyle) RoundedCornerShape(14.dp) else
            RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart =
                    capsuleBottomRadius,
                bottomEnd =
                    capsuleBottomRadius,
            )

        MiniPlayerSurface(
            style = miniPlayerBackground,
            pureBlack = pureBlack,
            colors = miniArtworkColors,
            animated =
                visualsActive &&
                    isPlaying &&
                    playbackState == Player.STATE_READY,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .miniPlayerSwipeMotion(
                        offset = { offsetXAnimatable.value },
                        layoutDirection = layoutDirection,
                    )
                    .clip(miniPlayerShape)
                    .background(Color.Transparent)
                    .border(
                        width = if (standardStyle) 0.dp else 1.dp,
                        color = if (standardStyle) Color.Transparent else
                            capsuleSurfaceOutline(
                                miniArtworkColors,
                                glass =
                                    miniPlayerBackground ==
                                        MiniPlayerBackgroundStyle.GLASS,
                            ),
                        shape =
                            miniPlayerShape,
                    ),
        ) {
            Row(
                verticalAlignment =
                    Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = foregroundAlpha().coerceIn(0f, 1f)
                        }
                        .padding(
                            horizontal = 10.dp,
                            vertical = 8.dp,
                        ),
            ) {
                CapsuleMiniPlayButton(
                    position = position,
                    duration = duration,
                    playbackState =
                        playbackState,
                    isPlaying =
                        isPlaying,
                    mediaMetadata =
                        mediaMetadata,
                    playerConnection =
                        playerConnection,
                    standardStyle = standardStyle,
                    interactionEnabled = foregroundInteractive,
                )

                Spacer(
                    Modifier.width(12.dp),
                )

                CapsuleMiniSongInfo(
                    mediaMetadata =
                        mediaMetadata,
                    visible = visualsActive,
                    modifier =
                        Modifier.weight(1f),
                )

                Spacer(
                    Modifier.width(8.dp),
                )

                mediaMetadata
                    ?.artists
                    ?.firstOrNull()
                    ?.id
                    ?.let { artistId ->
                        CapsuleSubscribeButton(
                            artistId =
                                artistId,
                            metadata =
                                mediaMetadata!!,
                            standardStyle = standardStyle,
                            interactionEnabled = foregroundInteractive,
                        )

                        Spacer(
                            Modifier.width(4.dp),
                        )
                    }

                CapsuleFavoriteButton(
                    liked =
                        currentSong
                            ?.song
                            ?.liked ==
                            true,
                    onClick =
                        playerConnection::toggleLike,
                    standardStyle = standardStyle,
                    interactionEnabled = foregroundInteractive,
                )
            }
        }
    }
}

@Composable
internal fun MiniPlayerSurface(
    style: MiniPlayerBackgroundStyle,
    pureBlack: Boolean,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Layout (standard or connected dock) must not override the chosen background.
    val contentColor = if (style == MiniPlayerBackgroundStyle.THEME && !pureBlack) {
        MaterialTheme.colorScheme.onSurface
    } else {
        CapsuleMiniText
    }
    Box(modifier) {
        CapsuleCompactSurfaceBackground(style, pureBlack, colors, Modifier.matchParentSize(), animated)
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}

@Composable
internal fun CapsuleCompactSurfaceBackground(
    style: MiniPlayerBackgroundStyle,
    pureBlack: Boolean,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surface
    val palette =
        listOf(
            colors.getOrElse(0) { primary },
            colors.getOrElse(1) { secondary },
            colors.getOrElse(2) { tertiary },
        )

    when (style) {
        MiniPlayerBackgroundStyle.THEME ->
            Box(
                modifier =
                    modifier.background(
                        if (pureBlack) {
                            Brush.linearGradient(
                                listOf(
                                    Color.Black,
                                    Color.Black,
                                ),
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(
                                    lerp(surface, primary, 0.12f).copy(
                                        alpha = 1f,
                                    ),
                                    lerp(surface, Color.Black, 0.3f).copy(
                                        alpha = 1f,
                                    ),
                                ),
                            )
                        },
                    ),
            )

        MiniPlayerBackgroundStyle.GRADIENT ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.MATTE_GRADIENT,
                colors = palette,
                modifier = modifier,
                compact = true,
                animated = false,
            )

        MiniPlayerBackgroundStyle.COLOR_FLOW ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.COLOR_FLOW,
                colors = palette,
                modifier = modifier,
                compact = true,
                animated = animated,
            )

        MiniPlayerBackgroundStyle.CAPSULE_STAR ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.CAPSULE_STAR,
                colors = palette,
                modifier = modifier,
                compact = true,
                animated = animated,
            )

        MiniPlayerBackgroundStyle.NEBULA ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.NEBULA,
                colors = palette,
                modifier = modifier,
                compact = true,
                animated = animated,
            )

        MiniPlayerBackgroundStyle.CAPSULE_GLOW ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.CAPSULE_GLOW,
                colors = palette,
                modifier = modifier,
                compact = true,
                animated = false,
            )

        MiniPlayerBackgroundStyle.GLASS ->
            CapsuleGlassSurface(
                colors = palette,
                modifier = modifier,
            )
    }
}

@Composable
private fun CapsuleMiniPlayButton(
    position: Long,
    duration: Long,
    playbackState: Int,
    isPlaying: Boolean,
    mediaMetadata: MediaMetadata?,
    playerConnection:
        com.nikhil.yt.playback.PlayerConnection,
    standardStyle: Boolean = false,
    interactionEnabled: Boolean = true,
) {
    val playLabel = stringResource(if (isPlaying) androidx.media3.ui.R.string.exo_controls_pause_description else R.string.play)

    Box(
        contentAlignment =
            Alignment.Center,
        modifier =
            Modifier
                .size(if (standardStyle) 46.dp else 50.dp)
                .miniPlayerProgress(position, duration),
    ) {
        Box(
            contentAlignment =
                Alignment.Center,
            modifier =
                Modifier
                    .size(if (standardStyle) 40.dp else 44.dp)
                    .clip(CircleShape)
                    .border(
                        1.dp,
                        CapsuleMiniOutline.copy(
                            alpha = 0.3f,
                        ),
                        CircleShape,
                    )
                    .semantics { contentDescription = playLabel }
                    .clickable(
                        enabled = interactionEnabled,
                        role = Role.Button,
                    ) {
                        if (
                            playbackState ==
                            Player.STATE_ENDED
                        ) {
                            playerConnection.player
                                .seekTo(0)

                            playerConnection.player
                                .playWhenReady =
                                true
                        } else {
                            playerConnection.player
                                .playWhenReady =
                                !playerConnection.player
                                    .playWhenReady
                        }
                    },
        ) {
            AsyncImage(
                model =
                    mediaMetadata
                        ?.thumbnailUrl,
                contentDescription =
                    null,
                contentScale =
                    ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
            )

            if (
                !isPlaying ||
                playbackState ==
                Player.STATE_ENDED
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Color.Black.copy(
                                alpha = 0.4f,
                            ),
                            CircleShape,
                        ),
                )

                Icon(
                    painter =
                        painterResource(
                            if (
                                playbackState ==
                                Player.STATE_ENDED
                            ) {
                                R.drawable.replay
                            } else {
                                R.drawable.play
                            },
                        ),
                    contentDescription =
                        null,
                    tint =
                        Color.White,
                    modifier =
                        Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CapsuleMiniSongInfo(
    mediaMetadata: MediaMetadata?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val playerConnection =
        LocalPlayerConnection.current

    val error by
        if (visible) {
            playerConnection
                ?.error
                ?.collectAsState()
                ?: remember {
                    androidx.compose.runtime.mutableStateOf<
                        androidx.media3.common.PlaybackException?
                    >(null)
                }
        } else {
            remember {
                androidx.compose.runtime.mutableStateOf<
                    androidx.media3.common.PlaybackException?
                >(null)
            }
        }

    Column(
        modifier = modifier,
        verticalArrangement =
            Arrangement.Center,
    ) {
        mediaMetadata?.let { metadata ->
            Text(
                text =
                    metadata.title,
                color =
                    LocalContentColor.current,
                fontSize = 14.sp,
                fontWeight =
                    FontWeight.Medium,
                maxLines = 1,
                overflow =
                    TextOverflow.Clip,
                modifier =
                    if (visible) {
                        Modifier.basicMarquee(
                            iterations = 1,
                            initialDelayMillis = 3000,
                            velocity = 30.dp,
                        )
                    } else {
                        Modifier
                    },
            )

            Row(
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                if (metadata.explicit) {
                    ExplicitBadge(
                        color = CapsuleMiniMuted,
                        size = 14.dp,
                        fontSize = 8.sp,
                    )

                    Spacer(
                        Modifier.width(5.dp),
                    )
                }

                Text(
                    text =
                        metadata.artists
                            .joinToString(
                                separator = ", ",
                            ) {
                                it.name
                            },
                    color =
                        LocalContentColor.current.copy(
                            alpha = 0.7f,
                        ),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Clip,
                    modifier =
                    if (visible) {
                        Modifier.basicMarquee(
                            iterations = 1,
                            initialDelayMillis = 3000,
                            velocity = 30.dp,
                        )
                    } else {
                        Modifier
                    },
                )
            }

            if (error != null) {
                Text(
                    text =
                        stringResource(R.string.error_unknown),
                    color =
                        CapsuleMiniError,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CapsuleSubscribeButton(
    artistId: String,
    metadata: MediaMetadata,
    standardStyle: Boolean = false,
    interactionEnabled: Boolean = true,
) {
    val database =
        LocalDatabase.current

    val libraryArtist by
        database
            .artist(artistId)
            .collectAsState(
                initial = null,
            )

    val isSubscribed =
        libraryArtist
            ?.artist
            ?.bookmarkedAt !=
            null

    val subscribeLabel =
        stringResource(if (isSubscribed) R.string.subscribed else R.string.subscribe)
    // Keep + / × in the same neutral family as the heart contour. A touch more
    // opacity keeps it legible without the subscribed × flashing brighter than the like icon.
    val subscribeTint = LocalContentColor.current.copy(alpha = 0.72f)

    Box(
        contentAlignment =
            Alignment.Center,
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(
                    width = if (standardStyle) 0.dp else 1.dp,
                    color = if (standardStyle) Color.Transparent else
                        if (isSubscribed) {
                            CapsuleMiniPrimary
                                .copy(
                                    alpha =
                                        0.5f,
                                )
                        } else {
                            CapsuleMiniOutline
                                .copy(
                                    alpha =
                                        0.3f,
                                )
                        },
                    shape =
                        CircleShape,
                )
                .background(
                    color = if (standardStyle) Color.Transparent else
                        if (isSubscribed) {
                            CapsuleMiniPrimary
                                .copy(
                                    alpha =
                                        0.1f,
                                )
                        } else {
                            Color.Transparent
                        },
                    shape =
                        CircleShape,
                )
                .semantics { contentDescription = subscribeLabel }
                .clickable(enabled = interactionEnabled) {
                    val artistInfo =
                        metadata.artists.firstOrNull { it.id == artistId }
                            ?: metadata.artists.firstOrNull()
                            ?: return@clickable
                    val artist =
                        libraryArtist?.artist
                            ?: ArtistEntity(
                                id = artistId,
                                name = artistInfo.name,
                                channelId = null,
                                thumbnailUrl = artistInfo.thumbnailUrl,
                            )
                    database.setArtistSubscribed(
                        artist = artist,
                        subscribed = !isSubscribed,
                    )
                },
    ) {
        CapsuleSubscribeIcon(
            subscribed = isSubscribed,
            tint = subscribeTint,
            modifier = Modifier.size(if (standardStyle) 26.dp else 20.dp),
            glyphScale = 1.45f,
            // An axis-aligned + reads much larger than the same strokes rotated into ×.
            // Reserve separate spans so both states feel the same optical size as the heart.
            plusSpanScale = 0.70f,
            crossSpanScale = 0.99f,
            strokeScale = 0.78f,
            flattenAlpha = true,
            keepOpticalFootprint = true,
        )
    }
}

@Composable
private fun CapsuleFavoriteButton(
    liked: Boolean,
    onClick: () -> Unit,
    standardStyle: Boolean = false,
    interactionEnabled: Boolean = true,
) {
    val favoriteTint = CapsuleFavoriteColors.selected(LocalContentColor.current)
    val favoriteInteraction = remember { MutableInteractionSource() }
    Box(
        contentAlignment =
            Alignment.Center,
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(
                    width = if (standardStyle) 0.dp else 1.dp,
                    color = if (standardStyle) Color.Transparent else
                        if (liked) {
                            favoriteTint
                                .copy(
                                    alpha =
                                        0.5f,
                                )
                        } else {
                            CapsuleMiniOutline
                                .copy(
                                    alpha =
                                        0.3f,
                                )
                        },
                    shape =
                        CircleShape,
                )
                .background(
                    color = if (standardStyle) Color.Transparent else
                        if (liked) {
                            favoriteTint
                                .copy(
                                    alpha =
                                        0.1f,
                                )
                        } else {
                            Color.Transparent
                        },
                    shape =
                        CircleShape,
                )
                .clickable(
                    enabled = interactionEnabled,
                    interactionSource = favoriteInteraction,
                    indication = null,
                    onClick = onClick,
                ),
    ) {
        CapsuleFavoriteIcon(
            liked = liked,
            interactionSource = favoriteInteraction,
            tint =
                if (liked) {
                    favoriteTint
                } else {
                    LocalContentColor.current.copy(alpha = 0.65f)
                },
            modifier =
                Modifier.size(if (standardStyle) 26.dp else 20.dp),
        )
    }
}
