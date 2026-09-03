/**
 * Capsule MUSIC
 *
 * Capsule Mini Player adapted from the original Capsule/Metrolist implementation.
 *
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.nikhil.yt.ui.screens.settings.DiscordPresenceManager
import com.nikhil.yt.utils.rememberPreference
import com.nikhil.yt.utils.rememberEnumPreference
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

@Composable
fun CapsuleMiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val playerConnection =
        LocalPlayerConnection.current ?: return

    val database =
        LocalDatabase.current

    val mediaMetadata by
        playerConnection.mediaMetadata.collectAsState()

    val playbackState by
        playerConnection.playbackState.collectAsState()

    val isPlaying by
        playerConnection.isPlaying.collectAsState()

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

    val miniPlayerBackground by
        rememberEnumPreference(
            MiniPlayerBackgroundStyleKey,
            defaultValue = MiniPlayerBackgroundStyle.CAPSULE_STAR,
        )

    val miniArtworkColors =
        rememberCapsuleArtworkColors(
            mediaMetadata = mediaMetadata,
            enabled =
                miniPlayerBackground !=
                    MiniPlayerBackgroundStyle.THEME,
        )

    val togetherState by
        playerConnection.service.togetherSessionState.collectAsState()

    val isListenTogetherGuest =
        (togetherState as? TogetherSessionState.Joined)
            ?.role is TogetherRole.Guest

    val swipeThumbnail =
        swipeThumbnailPref &&
            !isListenTogetherGuest

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

    val animationSpec =
        remember {
            spring<Float>(
                dampingRatio =
                    Spring.DampingRatioNoBouncy,
                stiffness =
                    Spring.StiffnessLow,
            )
        }

    val autoSwipeThreshold =
        remember(swipeSensitivity) {
            (
                600 /
                    (
                        1f +
                            kotlin.math.exp(
                                -(
                                    -11.44748 *
                                        swipeSensitivity +
                                        9.04945
                                ),
                            )
                        )
            ).roundToInt()
        }

    val canSkipPrevious =
        playerConnection.player.previousMediaItemIndex != -1

    val canSkipNext =
        playerConnection.player.nextMediaItemIndex != -1

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
                .padding(horizontal = 10.dp)
                .let { baseModifier ->
                    if (swipeThumbnail) {
                        baseModifier.pointerInput(
                            swipeSensitivity,
                            canSkipPrevious,
                            canSkipNext,
                        ) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragStartTime =
                                        System.currentTimeMillis()

                                    totalDragDistance =
                                        0f
                                },
                                onDragCancel = {
                                    coroutineScope.launch {
                                        offsetXAnimatable
                                            .animateTo(
                                                0f,
                                                animationSpec,
                                            )
                                    }
                                },
                                onHorizontalDrag = {
                                        _,
                                        dragAmount,
                                    ->
                                    val adjustedDragAmount =
                                        if (
                                            layoutDirection ==
                                            LayoutDirection.Rtl
                                        ) {
                                            -dragAmount
                                        } else {
                                            dragAmount
                                        }

                                    val tryingToSwipeRight =
                                        adjustedDragAmount > 0f

                                    val tryingToSwipeLeft =
                                        adjustedDragAmount < 0f

                                    val allowLeft =
                                        tryingToSwipeLeft &&
                                            canSkipNext

                                    val allowRight =
                                        tryingToSwipeRight &&
                                            canSkipPrevious

                                    /*
                                     * Important donor behaviour:
                                     * even at the edge of the queue the card may
                                     * return toward the center. It never gets stuck.
                                     */
                                    val canReturnToCenter =
                                        (
                                            tryingToSwipeRight &&
                                                !canSkipPrevious &&
                                                offsetXAnimatable.value < 0f
                                        ) ||
                                            (
                                                tryingToSwipeLeft &&
                                                    !canSkipNext &&
                                                    offsetXAnimatable.value > 0f
                                            )

                                    if (
                                        allowLeft ||
                                        allowRight ||
                                        canReturnToCenter
                                    ) {
                                        totalDragDistance +=
                                            kotlin.math.abs(
                                                adjustedDragAmount,
                                            )

                                        coroutineScope.launch {
                                            offsetXAnimatable
                                                .snapTo(
                                                    offsetXAnimatable.value +
                                                        adjustedDragAmount,
                                                )
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val dragDuration =
                                        System.currentTimeMillis() -
                                            dragStartTime

                                    val velocity =
                                        if (dragDuration > 0L) {
                                            totalDragDistance /
                                                dragDuration
                                        } else {
                                            0f
                                        }

                                    val currentOffset =
                                        offsetXAnimatable.value

                                    val minDistanceThreshold =
                                        50f

                                    val velocityThreshold =
                                        (
                                            swipeSensitivity *
                                                -8.25f
                                        ) + 8.5f

                                    val shouldChangeSong =
                                        (
                                            kotlin.math.abs(
                                                currentOffset,
                                            ) >
                                                minDistanceThreshold &&
                                                velocity >
                                                velocityThreshold
                                        ) ||
                                            (
                                                kotlin.math.abs(
                                                    currentOffset,
                                                ) >
                                                    autoSwipeThreshold
                                            )

                                    if (shouldChangeSong) {
                                        if (
                                            currentOffset > 0f &&
                                            canSkipPrevious
                                        ) {
                                            seekPreviousPreservingPlayback()
                                        } else if (
                                            currentOffset <= 0f &&
                                            canSkipNext
                                        ) {
                                            seekNextPreservingPlayback()
                                        }
                                    }

                                    coroutineScope.launch {
                                        offsetXAnimatable
                                            .animateTo(
                                                0f,
                                                animationSpec,
                                            )
                                    }
                                },
                            )
                        }
                    } else {
                        baseModifier
                    }
                },
    ) {
        val capsuleBottomRadius by
            animateDpAsState(
                targetValue =
                    if (capsuleDockVisible) {
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

        val miniPlayerShape =
            RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart =
                    capsuleBottomRadius,
                bottomEnd =
                    capsuleBottomRadius,
            )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .offset {
                        IntOffset(
                            offsetXAnimatable.value
                                .roundToInt(),
                            0,
                        )
                    }
                    .clip(miniPlayerShape)
                    .background(Color.Transparent)
                    .border(
                        width = 1.dp,
                        color =
                            capsuleSurfaceOutline(
                                miniArtworkColors,
                            ),
                        shape =
                            miniPlayerShape,
                    ),
        ) {
            CapsuleCompactSurfaceBackground(
                style = miniPlayerBackground,
                pureBlack = pureBlack,
                colors = miniArtworkColors,
                modifier = Modifier.fillMaxSize(),
                allowTransparency = true,
            )

            Row(
                verticalAlignment =
                    Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxSize()
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
                )

                Spacer(
                    Modifier.width(12.dp),
                )

                CapsuleMiniSongInfo(
                    mediaMetadata =
                        mediaMetadata,
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
                        )

                        Spacer(
                            Modifier.width(8.dp),
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
                )
            }
        }
    }
}

@Composable
internal fun CapsuleCompactSurfaceBackground(
    style: MiniPlayerBackgroundStyle,
    pureBlack: Boolean,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    allowTransparency: Boolean = false,
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
        ).map(::capsuleMutedArtworkColor)
    val surfaceAlpha = if (allowTransparency) 0.9f else 1f

    when (style) {
        MiniPlayerBackgroundStyle.THEME ->
            Box(
                modifier =
                    modifier.background(
                        if (pureBlack) {
                            Brush.linearGradient(
                                listOf(
                                    Color.Black.copy(alpha = surfaceAlpha),
                                    Color.Black.copy(alpha = surfaceAlpha),
                                ),
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(
                                    lerp(surface, primary, 0.12f).copy(
                                        alpha = surfaceAlpha,
                                    ),
                                    lerp(surface, Color.Black, 0.3f).copy(
                                        alpha = surfaceAlpha,
                                    ),
                                ),
                            )
                        },
                    ),
            )

        MiniPlayerBackgroundStyle.GRADIENT ->
            Box(
                modifier =
                    modifier.background(
                        Brush.linearGradient(
                            listOf(
                                lerp(palette[0], Color(0xFF05060B), 0.52f)
                                    .copy(alpha = surfaceAlpha),
                                lerp(palette[1], Color(0xFF05060B), 0.62f)
                                    .copy(alpha = surfaceAlpha),
                                lerp(palette[2], Color(0xFF05060B), 0.7f)
                                    .copy(alpha = surfaceAlpha),
                            ),
                        ),
                    ),
            )

        MiniPlayerBackgroundStyle.COLOR_FLOW ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.COLOR_FLOW,
                colors = palette,
                modifier = modifier,
                compact = true,
                allowTransparency = allowTransparency,
                animated = animated,
            )

        MiniPlayerBackgroundStyle.CAPSULE_STAR ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.CAPSULE_STAR,
                colors = palette,
                modifier = modifier,
                compact = true,
                allowTransparency = allowTransparency,
                animated = animated,
            )

        MiniPlayerBackgroundStyle.AURORA ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.AURORA,
                colors = palette,
                modifier = modifier,
                compact = true,
                allowTransparency = allowTransparency,
                animated = animated,
            )

        MiniPlayerBackgroundStyle.NEBULA ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.NEBULA,
                colors = palette,
                modifier = modifier,
                compact = true,
                allowTransparency = allowTransparency,
                animated = animated,
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
) {
    val progress =
        if (duration > 0L) {
            (
                position.toFloat() /
                    duration.toFloat()
            ).coerceIn(
                0f,
                1f,
            )
        } else {
            0f
        }

    val trackColor =
        CapsuleMiniOutline.copy(
            alpha = 0.2f,
        )

    Box(
        contentAlignment =
            Alignment.Center,
        modifier =
            Modifier
                .size(50.dp)
                .drawWithContent {
                    drawContent()

                    val stroke =
                        Stroke(
                            width =
                                2.dp.toPx(),
                            cap =
                                StrokeCap.Round,
                        )

                    val diameter =
                        size.minDimension

                    val topLeft =
                        Offset(
                            (
                                size.width -
                                    diameter
                            ) / 2f,
                            (
                                size.height -
                                    diameter
                            ) / 2f,
                        )

                    drawArc(
                        color =
                            trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft =
                            topLeft,
                        size =
                            Size(
                                diameter,
                                diameter,
                            ),
                        style =
                            stroke,
                    )

                    drawArc(
                        color =
                            CapsuleMiniPrimary,
                        startAngle =
                            -90f,
                        sweepAngle =
                            360f * progress,
                        useCenter = false,
                        topLeft =
                            topLeft,
                        size =
                            Size(
                                diameter,
                                diameter,
                            ),
                        style =
                            stroke,
                    )
                },
    ) {
        Box(
            contentAlignment =
                Alignment.Center,
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(
                        1.dp,
                        CapsuleMiniOutline.copy(
                            alpha = 0.3f,
                        ),
                        CircleShape,
                    )
                    .clickable {
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
    modifier: Modifier = Modifier,
) {
    val playerConnection =
        LocalPlayerConnection.current

    val error by
        playerConnection
            ?.error
            ?.collectAsState()
            ?: remember {
                androidx.compose.runtime.mutableStateOf<
                    androidx.media3.common.PlaybackException?
                >(null)
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
                    CapsuleMiniText,
                fontSize = 14.sp,
                fontWeight =
                    FontWeight.Medium,
                maxLines = 1,
                overflow =
                    TextOverflow.Clip,
                modifier =
                    Modifier.basicMarquee(
                        iterations = 1,
                        initialDelayMillis =
                            3000,
                        velocity =
                            30.dp,
                    ),
            )

            Row(
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                if (metadata.explicit) {
                    Box(
                        modifier =
                            Modifier
                                .size(
                                    width =
                                        14.dp,
                                    height =
                                        14.dp,
                                )
                                .clip(
                                    RoundedCornerShape(
                                        2.dp,
                                    ),
                                )
                                .border(
                                    1.dp,
                                    CapsuleMiniMuted,
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
                                CapsuleMiniMuted,
                            fontSize = 8.sp,
                            lineHeight = 8.sp,
                            fontWeight =
                                FontWeight.Bold,
                        )
                    }

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
                        CapsuleMiniText.copy(
                            alpha = 0.7f,
                        ),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Clip,
                    modifier =
                        Modifier.basicMarquee(
                            iterations = 1,
                            initialDelayMillis =
                                3000,
                            velocity =
                                30.dp,
                        ),
                )
            }

            if (error != null) {
                Text(
                    text =
                        "Playback error",
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

    Box(
        contentAlignment =
            Alignment.Center,
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color =
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
                    color =
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
                .clickable {
                    database.transaction {
                        val artist =
                            libraryArtist
                                ?.artist

                        if (artist != null) {
                            update(
                                artist.toggleLike(),
                            )
                        } else {
                            metadata.artists
                                .firstOrNull()
                                ?.let {
                                        artistInfo,
                                    ->
                                    insert(
                                        ArtistEntity(
                                            id =
                                                artistInfo.id
                                                    ?: "",
                                            name =
                                                artistInfo.name,
                                            channelId =
                                                null,
                                            thumbnailUrl =
                                                null,
                                        ).toggleLike(),
                                    )
                                }
                        }
                    }
                },
    ) {
        Icon(
            painter =
                painterResource(
                    if (isSubscribed) {
                        R.drawable.subscribed
                    } else {
                        R.drawable.person
                    },
                ),
            contentDescription =
                null,
            tint =
                if (isSubscribed) {
                    CapsuleMiniPrimary
                } else {
                    CapsuleMiniMuted
                },
            modifier =
                Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CapsuleFavoriteButton(
    liked: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment =
            Alignment.Center,
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color =
                        if (liked) {
                            CapsuleMiniError
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
                    color =
                        if (liked) {
                            CapsuleMiniError
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
                    onClick = onClick,
                ),
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
                if (liked) {
                    CapsuleMiniError
                } else {
                    CapsuleMiniMuted
                },
            modifier =
                Modifier.size(20.dp),
        )
    }
}
