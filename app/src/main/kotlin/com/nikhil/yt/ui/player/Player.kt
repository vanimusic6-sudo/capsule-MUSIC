/*
 * Capsule MUSIC
 *
 * Capsule is the only full-player skin. Playback, queue, lyrics providers and
 * media-session behaviour remain owned by the existing application services.
 *
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.navigation.NavController
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.constants.DarkModeKey
import com.nikhil.yt.constants.PlayerBackgroundStyle
import com.nikhil.yt.constants.PlayerBackgroundStyleKey
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.component.BottomSheet
import com.nikhil.yt.ui.component.BottomSheetState
import com.nikhil.yt.ui.component.LocalBottomSheetPageState
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.rememberBottomSheetState
import com.nikhil.yt.ui.menu.PlayerMenu
import com.nikhil.yt.ui.screens.settings.DarkMode
import com.nikhil.yt.ui.utils.ShowMediaInfo
import com.nikhil.yt.utils.rememberEnumPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    var showInlineLyrics by rememberSaveable {
        mutableStateOf(false)
    }

    val playerBackground by
        rememberEnumPreference(
            key = PlayerBackgroundStyleKey,
            defaultValue = PlayerBackgroundStyle.CAPSULE_STAR,
        )

    val systemDark = isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.ON)
    val useBlackBackground =
        remember(systemDark, darkTheme, pureBlack) {
            val effectiveDark =
                if (darkTheme == DarkMode.AUTO) systemDark else darkTheme == DarkMode.ON
            effectiveDark && pureBlack
        }

    val playbackState by playerConnection.playbackState.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val currentSongLiked = currentSong?.song?.liked == true
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val automix by playerConnection.service.automixItems.collectAsState()

    var position by remember(mediaMetadata?.id) {
        mutableLongStateOf(playerConnection.player.currentPosition.coerceAtLeast(0L))
    }
    var duration by remember(mediaMetadata?.id) {
        mutableLongStateOf(playerConnection.player.duration)
    }
    var sliderPosition by remember(mediaMetadata?.id) {
        mutableStateOf<Long?>(null)
    }

    LaunchedEffect(mediaMetadata?.id, playbackState, isPlaying, sliderPosition) {
        if (sliderPosition != null) return@LaunchedEffect

        while (isActive) {
            position = playerConnection.player.currentPosition.coerceAtLeast(0L)
            duration =
                playerConnection.player.duration
                    .takeIf { it > 0L && it != C.TIME_UNSET }
                    ?: C.TIME_UNSET
            delay(
                when {
                    !isPlaying -> 1_000L
                    state.isExpanded -> 250L
                    else -> 500L
                },
            )
        }
    }

    LaunchedEffect(canSkipNext, automix) {
        if (!canSkipNext) {
            automix.firstOrNull()?.let { next ->
                playerConnection.service.addToQueueAutomix(next, 0)
            }
        }
    }

    val needsArtworkPalette =
        playerBackground != PlayerBackgroundStyle.DEFAULT
    val gradientColors =
        rememberCapsuleArtworkColors(
            mediaMetadata = mediaMetadata,
            enabled = needsArtworkPalette,
        )

    val textColor =
        when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.onBackground
            else -> Color.White
        }

    val queueSheetState =
        rememberBottomSheetState(
            dismissedBound = 0.dp,
            expandedBound = state.expandedBound,
            collapsedBound = 0.dp,
            initialAnchor = 1,
        )

    BackHandler(
        enabled =
            showInlineLyrics ||
                (!queueSheetState.isCollapsed && !queueSheetState.isDismissed) ||
                (!state.isCollapsed && !state.isDismissed),
    ) {
        when {
            showInlineLyrics -> showInlineLyrics = false
            !queueSheetState.isCollapsed && !queueSheetState.isDismissed ->
                queueSheetState.collapseSoft()
            !state.isCollapsed && !state.isDismissed ->
                state.collapseSoft()
        }
    }

    BottomSheet(
        state = state,
        modifier = modifier,
        backgroundColor = playerSurfaceColor(
            state = state,
            playerBackground = playerBackground,
            useBlackBackground = useBlackBackground,
        ),
        onDismiss = {
            playerConnection.service.stopAndClearPlayback()
        },
        collapsedContent = {
            MiniPlayer(
                position = position,
                duration = duration,
                pureBlack = pureBlack,
            )
        },
    ) {
        val enrichedMetadata =
            remember(mediaMetadata, currentSong) {
                val metadata = mediaMetadata ?: return@remember null
                if (metadata.album != null) return@remember metadata

                val databaseAlbum = currentSong?.album
                val databaseAlbumId = currentSong?.song?.albumId
                when {
                    databaseAlbum != null ->
                        metadata.copy(
                            album =
                                MediaMetadata.Album(
                                    id = databaseAlbum.id,
                                    title = databaseAlbum.title,
                                ),
                        )
                    databaseAlbumId != null ->
                        metadata.copy(
                            album =
                                MediaMetadata.Album(
                                    id = databaseAlbumId,
                                    title = currentSong?.song?.albumName.orEmpty(),
                                ),
                        )
                    else -> metadata
                }
            }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.isCollapsed) {
                PlayerBackground(
                    playerBackground = playerBackground,
                    gradientColors = gradientColors,
                )
            }

            enrichedMetadata?.let { metadata ->
                CapsulePlayerLyricsHost(
                    showLyrics = showInlineLyrics,
                    mediaMetadata = metadata,
                    sliderPosition = sliderPosition,
                    position = position,
                    duration = duration,
                    onSeekPreview = {
                        sliderPosition = it
                    },
                    onSeekFinished = {
                        sliderPosition?.let {
                            playerConnection.player.seekTo(it)
                            position = it
                        }
                        sliderPosition = null
                    },
                    textColor = textColor,
                    liked = currentSongLiked,
                    playerConnection = playerConnection,
                    navController = navController,
                    playerState = state,
                    queueState = queueSheetState,
                    onShowLyrics = {
                        showInlineLyrics = true
                    },
                    onHideLyrics = {
                        showInlineLyrics = false
                    },
                    onShowMenu = {
                        menuState.show {
                            PlayerMenu(
                                mediaMetadata = metadata,
                                navController = navController,
                                playerBottomSheetState = state,
                                onShowDetailsDialog = {
                                    bottomSheetPageState.show {
                                        ShowMediaInfo(metadata.id)
                                    }
                                },
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                )
            }
        }

        Queue(
            state = queueSheetState,
            playerBottomSheetState = state,
            navController = navController,
            backgroundColor =
                if (useBlackBackground) {
                    Color.Black
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            onBackgroundColor =
                if (useBlackBackground) {
                    Color.White
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            pureBlack = pureBlack,
        )
    }
}

@Composable
private fun CapsulePlayerLyricsHost(
    showLyrics: Boolean,
    mediaMetadata: MediaMetadata,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    onSeekPreview: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    textColor: Color,
    liked: Boolean,
    playerConnection: com.nikhil.yt.playback.PlayerConnection,
    navController: NavController,
    playerState: BottomSheetState,
    queueState: BottomSheetState,
    onShowLyrics: () -> Unit,
    onHideLyrics: () -> Unit,
    onShowMenu: () -> Unit,
) {
    val easing = remember {
        CubicBezierEasing(0.35f, 0f, 0.20f, 1f)
    }

    AnimatedContent(
        targetState = showLyrics,
        transitionSpec = {
            val enterDuration = 330
            val exitDuration = 300
            val scale = 0.994f

            if (targetState) {
                (
                    fadeIn(tween(enterDuration, easing = easing)) +
                        slideInVertically(
                            animationSpec = tween(enterDuration, easing = easing),
                            initialOffsetY = { fullHeight -> fullHeight / 28 },
                        ) +
                        scaleIn(
                            animationSpec = tween(enterDuration, easing = easing),
                            initialScale = scale,
                        )
                ).togetherWith(
                    fadeOut(tween(exitDuration, easing = easing)) +
                        slideOutVertically(
                            animationSpec = tween(exitDuration, easing = easing),
                            targetOffsetY = { fullHeight -> -fullHeight / 36 },
                        ) +
                        scaleOut(
                            animationSpec = tween(exitDuration, easing = easing),
                            targetScale = scale,
                        ),
                )
            } else {
                (
                    fadeIn(tween(enterDuration, easing = easing)) +
                        slideInVertically(
                            animationSpec = tween(enterDuration, easing = easing),
                            initialOffsetY = { fullHeight -> -fullHeight / 36 },
                        ) +
                        scaleIn(
                            animationSpec = tween(enterDuration, easing = easing),
                            initialScale = scale,
                        )
                ).togetherWith(
                    fadeOut(tween(exitDuration, easing = easing)) +
                        slideOutVertically(
                            animationSpec = tween(exitDuration, easing = easing),
                            targetOffsetY = { fullHeight -> fullHeight / 28 },
                        ) +
                        scaleOut(
                            animationSpec = tween(exitDuration, easing = easing),
                            targetScale = scale,
                        ),
                )
            }
        },
        label = "CapsulePlayerLyricsTransition",
    ) { lyricsVisible ->
        if (lyricsVisible) {
            LyricsScreen(
                mediaMetadata = mediaMetadata,
                onBackClick = onHideLyrics,
            )
        } else {
            CapsulePlayerContent(
                mediaMetadata = mediaMetadata,
                sliderPosition = sliderPosition,
                positionMs = position,
                durationMs = duration,
                onSeekPreview = onSeekPreview,
                onSeekFinished = onSeekFinished,
                textColor = textColor,
                liked = liked,
                playerConnection = playerConnection,
                onToggleLike = playerConnection::toggleLike,
                onExpandQueue = queueState::expandSoft,
                onArtworkClick = onShowLyrics,
                onArtistSelected = { artist ->
                    artist.id?.let { artistId ->
                        onHideLyrics()
                        navController.navigate("artist/$artistId")
                        playerState.collapseSoft()
                    }
                },
                onMenuClick = onShowMenu,
                context = LocalContext.current,
                bottomPadding = 0.dp,
            )
        }
    }
}

@Composable
private fun playerSurfaceColor(
    state: BottomSheetState,
    playerBackground: PlayerBackgroundStyle,
    useBlackBackground: Boolean,
): Color {
    val progress =
        ((state.value - state.collapsedBound) /
            (state.expandedBound - state.collapsedBound))
            .coerceIn(0f, 1f)
    val fadeProgress =
        if (progress < 0.2f) {
            ((0.2f - progress) / 0.2f).coerceIn(0f, 1f)
        } else {
            0f
        }

    return if (useBlackBackground) {
        Color.Black.copy(alpha = 1f - fadeProgress)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 1f - fadeProgress)
    }
}
