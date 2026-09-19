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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import com.nikhil.yt.ui.motion.CapsuleMotion
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

    /*
     * The clock that drives the progress bar and the time readouts stops with the screen.
     *
     * Composition does not stop when the app is backgrounded, so this loop used to keep waking
     * every 300ms to read a position nobody could see and recompose the whole player around it.
     * Playback position is the service's business, not this screen's; this is only the readout.
     * Coming back restarts the loop, which reads immediately, so nothing is stale on return.
     */
    val onScreen = appIsOnScreen()

    LaunchedEffect(mediaMetadata?.id, playbackState, isPlaying, sliderPosition, onScreen) {
        if (sliderPosition != null) return@LaunchedEffect
        if (!onScreen) return@LaunchedEffect

        while (isActive) {
            position = playerConnection.player.currentPosition.coerceAtLeast(0L)
            duration =
                playerConnection.player.duration
                    .takeIf { it > 0L && it != C.TIME_UNSET }
                    ?: C.TIME_UNSET
            delay(
                when {
                    !isPlaying -> 1_000L
                    state.isExpanded -> 300L
                    else -> 550L
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

/**
 * The lyrics sheet has a character of its own, and it had to be made more than nominally different.
 *
 * Both surfaces used to rise from the bottom and resolve a uniform scale, which is one animation
 * played twice however the numbers differ — the eye reads the *geometry*, not the constants. What
 * separates them now is where each one is anchored and along which axis it moves:
 *
 * - the player is anchored at the **bottom**, at the dock it folds into, and scales on both axes. It
 *   is an object shrinking towards a place.
 * - the lyrics are anchored at the **top** and stretch on the vertical axis alone. Nothing about
 *   them gets wider or narrower; the sheet unrolls downward from its own top edge, the way a page
 *   is pulled out rather than a card zoomed in.
 *
 * Those are opposite anchors and different axes, so the two cannot be mistaken for each other even
 * though the idea — rise, open out, settle — is the one that was there before.
 */
private const val LyricsOpenWindow = 0.70f

/** Vertical only. The horizontal axis is left at exactly 1 throughout, which is the whole point. */
private const val LyricsUnrollStretch = 0.045f
private const val LyricsOpenFade = 0.90f
private const val LyricsTravelMillis = 480

/**
 * Softer off the mark than the player's, and a touch longer.
 *
 * The sheet should feel lighter than the thing it covers: the player is a slab being moved, the
 * lyrics are a page being drawn out. Spending even less distance in the first frames is what carries
 * that difference in time as well as in shape.
 */
private val LyricsEasing = CubicBezierEasing(0.42f, 0f, 0.28f, 1f)

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
    val lyricsMotion = remember {
        Animatable(if (showLyrics) 1f else 0f)
    }
    var lyricsLayerMounted by remember {
        mutableStateOf(showLyrics)
    }

    /*
     * Mount/unmount only at the ends of the transition. The animated Float and velocity are read by
     * graphicsLayer below, so the expensive player/lyrics subtrees are not recomposed on every frame.
     * The spring stays under-damped for impact, but lower stiffness makes that impact arrive softly.
     */
    LaunchedEffect(showLyrics) {
        if (showLyrics) {
            lyricsLayerMounted = true
        }

        /*
         * A decelerating tween, not a spring. A critically damped spring approaches its target
         * asymptotically and is cut off at its visibility threshold, so the last pixels are covered
         * by a jump — the sheet appeared to snap onto the edge as if magnetised. A tween lands on
         * the value exactly, at a known time, with its speed already down to nothing.
         */
        lyricsMotion.animateTo(
            targetValue = if (showLyrics) 1f else 0f,
            animationSpec = tween(durationMillis = LyricsTravelMillis, easing = LyricsEasing),
        )

        if (!showLyrics) {
            lyricsLayerMounted = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val reaction = lyricsMotion.value.coerceIn(0f, 1f)
                        translationY = -2.75f * reaction
                        scaleX = 1f - 0.00070f * reaction
                        scaleY = 1f - 0.00100f * reaction
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
        ) {
            if (design == CapsulePlayerDesign.IMMERSIVE) {
                /*
                 * A separate screen rather than a third set of branches through the other one.
                 * Immersion inverts the layout — the artwork is the screen and the controls are
                 * guests that leave — so threading it through a design flag would have meant a
                 * conditional on nearly every line of a composable that already carries two.
                 */
                CapsuleImmersiveContent(
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
                    onArtistSelected = { artist ->
                        artist.id?.let { artistId ->
                            onHideLyrics()
                            navController.navigate("artist/$artistId")
                            playerState.collapseSoft()
                        }
                    },
                    bottomPadding = 0.dp,
                    open = !playerState.isCollapsed,
                )
                return@Box
            }

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
                open = !playerState.isCollapsed,
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val fullHeightPx = constraints.maxHeight.toFloat()

            if (lyricsLayerMounted) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                /*
                                 * Travel, settle and blur all read the same progress, so the lyrics
                                 * sheet lands exactly once and holds still. The previous version
                                 * added a velocity-derived lag to its translation and a squash
                                 * driven by spring overshoot; both fought the travel they were
                                 * layered on and produced the jitter at the end of the transition.
                                 */
                                val travelled = lyricsMotion.value.coerceIn(0f, 1f)
                                translationY =
                                    ((1f - travelled) * fullHeightPx).coerceAtLeast(0f)

                                /*
                                 * Unrolling, not settling. The sheet is over-tall at the start and
                                 * draws down to its true height from its own top edge; its width
                                 * never changes at all. That is what keeps it from reading as the
                                 * player's fold played in reverse.
                                 *
                                 * No blur — a full-screen RenderEffect costs an offscreen buffer
                                 * every frame, which is what made these surfaces stall the first
                                 * time they were used.
                                 */
                                val opening =
                                    CapsuleMotion.approach(
                                        progress = travelled,
                                        window = LyricsOpenWindow,
                                    )
                                val remaining = 1f - opening
                                scaleX = 1f
                                scaleY = 1f + LyricsUnrollStretch * remaining
                                alpha = 1f - LyricsOpenFade * remaining
                                transformOrigin = TransformOrigin(0.5f, 0f)
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
