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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.navigation.NavController
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.constants.CapsulePlayerDesign
import com.nikhil.yt.constants.CapsulePlayerDesignKey
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
import kotlin.math.abs

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

    val playerDesign by rememberEnumPreference(CapsulePlayerDesignKey, CapsulePlayerDesign.SUPER)

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

    /*
     * Playback metadata can arrive before Room relations. Merge the richer
     * local artist/album information as soon as it becomes available, but keep
     * the same MediaMetadata object shape used by the rest of the player.
     */
    val enrichedMetadata =
        remember(mediaMetadata, currentSong) {
            val metadata = mediaMetadata ?: return@remember null
            val databaseArtists = currentSong?.artists?.associateBy { it.id }.orEmpty()
            val enrichedArtists =
                metadata.artists.map { artist ->
                    if (!artist.thumbnailUrl.isNullOrBlank()) {
                        artist
                    } else {
                        artist.copy(
                            thumbnailUrl = artist.id?.let { databaseArtists[it]?.thumbnailUrl },
                        )
                    }
                }

            val album =
                metadata.album
                    ?: currentSong?.album?.let {
                        MediaMetadata.Album(
                            id = it.id,
                            title = it.title,
                        )
                    }
                    ?: currentSong?.song?.albumId?.let { albumId ->
                        MediaMetadata.Album(
                            id = albumId,
                            title = currentSong?.song?.albumName.orEmpty(),
                        )
                    }

            metadata.copy(
                artists = enrichedArtists,
                thumbnailUrl = metadata.thumbnailUrl ?: currentSong?.song?.thumbnailUrl,
                album = album,
            )
        }

    /* Start cover + portrait work while the full player is still collapsed. */
    PreloadCapsuleTrackAssets(enrichedMetadata)

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
            mediaMetadata = enrichedMetadata,
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
        backgroundColor = playerSurfaceColor(useBlackBackground),
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
        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.isCollapsed) {
                PlayerBackground(
                    playerBackground = playerBackground,
                    gradientColors = gradientColors,
                )
            }

            enrichedMetadata?.let { metadata ->
                CapsulePlayerLyricsHost(
                    design = playerDesign,
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
    design: CapsulePlayerDesign,
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
    /*
     * Unlike AnimatedVisibility, this keeps one continuous physical state. If the user reverses the
     * transition before it finishes, Animatable starts from the current position and velocity rather
     * than spawning a second entrance/exit animation. That continuity is a large part of the tactile
     * feeling of the favourite-heart interaction.
     */
    val lyricsMotion = remember {
        Animatable(if (showLyrics) 1f else 0f)
    }

    LaunchedEffect(showLyrics) {
        lyricsMotion.animateTo(
            targetValue = if (showLyrics) 1f else 0f,
            animationSpec =
                spring(
                    dampingRatio = if (showLyrics) 0.80f else 0.86f,
                    stiffness = if (showLyrics) 270f else 350f,
                ),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val playerReaction = lyricsMotion.value.coerceIn(0f, 1f)

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = -4f * playerReaction
                        scaleX = 1f - 0.0012f * playerReaction
                        scaleY = 1f - 0.0018f * playerReaction
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
        ) {
            CapsulePlayerContent(
                design = design,
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
                onCollapse = playerState::collapseSoft,
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

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val progress = lyricsMotion.value
            val fullHeightPx = constraints.maxHeight.toFloat()
            val baseTranslation = (1f - progress) * fullHeightPx
            val velocity = lyricsMotion.velocity
            val velocityWeight = (abs(velocity) / 4.5f).coerceIn(0f, 1f)
            val inertialLag = (velocity * 4.5f).coerceIn(-14f, 14f)
            val shouldComposeLyrics =
                showLyrics || lyricsMotion.isRunning || progress > 0.001f

            if (shouldComposeLyrics) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationY = baseTranslation + inertialLag
                                scaleX = 1f + 0.0015f * velocityWeight
                                scaleY = 1f - 0.0035f * velocityWeight
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            },
                ) {
                    LyricsScreen(
                        mediaMetadata = mediaMetadata,
                        onBackClick = onHideLyrics,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun playerSurfaceColor(useBlackBackground: Boolean): Color =
    if (useBlackBackground) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surface
    }
