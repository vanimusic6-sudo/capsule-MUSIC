/*
 * Capsule MUSIC
 *
 * Capsule is the only full-player skin. Playback, queue, lyrics providers and
 * media-session behaviour remain owned by the existing application services.
 *
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.player

import android.content.res.Configuration
import android.net.Uri

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun capsulePlayerDesignForOrientation(
    selected: CapsulePlayerDesign,
    isLandscape: Boolean,
): CapsulePlayerDesign =
    if (isLandscape) {
        CapsulePlayerDesign.LIGHT
    } else {
        selected
    }

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
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Custom Capsule layouts are portrait-authored. Landscape always presents the original
    // fixed Light layout without changing the saved preference. CapsulePlayerContent deliberately
    // keeps CanvasV2 out of landscape, so the short/wide viewport cannot rewrite portrait state.
    val effectivePlayerDesign =
        capsulePlayerDesignForOrientation(
            selected = playerDesign,
            isLandscape = isLandscape,
        )

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

    val onScreen = appIsOnScreen()

    /*
     * Start cover + portrait work while the player is collapsed, but never while the whole app is
     * backgrounded. Returning to the app remounts this immediately, long before the user can finish
     * an expand gesture.
     */
    if (onScreen) {
        PreloadCapsuleTrackAssets(enrichedMetadata)
    }

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
    LaunchedEffect(
        mediaMetadata?.id,
        playbackState,
        isPlaying,
        sliderPosition,
        onScreen,
        showInlineLyrics,
        state.isDismissed,
    ) {
        if (sliderPosition != null) return@LaunchedEffect
        // Lyrics owns its own visible progress readout. Do not keep the hidden player clock alive
        // underneath it, and do not poll anything after the whole sheet was dismissed.
        if (!onScreen || showInlineLyrics || state.isDismissed) return@LaunchedEffect

        while (isActive) {
            position = playerConnection.player.currentPosition.coerceAtLeast(0L)
            duration =
                playerConnection.player.duration
                    .takeIf { it > 0L && it != C.TIME_UNSET }
                    ?: C.TIME_UNSET
            delay(
                when {
                    !isPlaying -> 1_500L
                    state.isExpanded -> 300L
                    else -> 1_000L
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
        onScreen &&
            !state.isCollapsed &&
            !state.isDismissed &&
            (
                playerBackground != PlayerBackgroundStyle.DEFAULT ||
                    effectivePlayerDesign == CapsulePlayerDesign.IMMERSIVE
            )
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

    /*
     * Keep one frozen procedural phase for the entire Lyrics session and both transitions. This
     * prevents colour drift between player/Lyrics backdrops and avoids redrawing two procedural
     * backgrounds while the transition itself is already animating.
     */
    var freezeBackdropAfterLyricsSettles by remember {
        mutableStateOf(showInlineLyrics)
    }
    LaunchedEffect(showInlineLyrics) {
        if (showInlineLyrics) {
            /*
             * Freeze the shared procedural phase immediately. The old path kept the clock running
             * through the entire opening, forcing both the still-visible player backdrop and the
             * incoming Lyrics backdrop to redraw every frame. Holding the current phase preserves
             * the exact colours while making the transition essentially static-background work.
             */
            freezeBackdropAfterLyricsSettles = true
        }
        // The host releases this freeze only when both closing stages have completed.
    }

    val backdropNeedsClock =
        when (playerBackground) {
            PlayerBackgroundStyle.GLOW_ANIMATED,
            PlayerBackgroundStyle.CAPSULE_STAR,
            PlayerBackgroundStyle.NEBULA,
            -> true

            else -> false
        }
    val backdropTimelineRunning =
        backdropNeedsClock &&
            onScreen &&
            isPlaying &&
            playbackState == Player.STATE_READY &&
            !freezeBackdropAfterLyricsSettles
    val sharedBackdropAnimationTime =
        rememberCapsuleAnimationTime(
            compact = false,
            running = backdropTimelineRunning,
        )

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
            val miniVisible =
                onScreen &&
                    !state.isExpanded &&
                    !state.isDismissed
            MiniPlayer(
                // Avoid propagating the full-player's 300ms progress clock into a subtree that is
                // completely covered. As soon as the collapse animation leaves the expanded
                // anchor, live values are restored before the mini-player is visibly exposed.
                position = if (miniVisible) position else 0L,
                duration = if (miniVisible) duration else 0L,
                pureBlack = pureBlack,
                visible = miniVisible,
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            /*
             * Immersion paints its own floor from the artwork, and a chosen backdrop behind it
             * would be a second picture competing with the cover.
             */
            if (
                !state.isCollapsed &&
                !state.isDismissed &&
                effectivePlayerDesign != CapsulePlayerDesign.IMMERSIVE
            ) {
                /*
                 * Keep the already-rendered player backdrop under the lyrics travel. Lyrics opens
                 * from off-screen, so unmounting PlayerBackground as soon as showLyrics=true
                 * exposed the BottomSheet's neutral Surface color through the uncovered area and
                 * looked like the player suddenly turned grey.
                 *
                 * The expensive part still sleeps: once Lyrics owns the screen the backdrop is
                 * rendered as a static cached frame, with no procedural animation clock.
                 */
                PlayerBackground(
                    playerBackground = playerBackground,
                    gradientColors = gradientColors,
                    animated = backdropTimelineRunning,
                    sharedAnimationTime = sharedBackdropAnimationTime,
                )
            }

            enrichedMetadata?.let { metadata ->
                CapsulePlayerLyricsHost(
                    design = effectivePlayerDesign,
                    showLyrics = showInlineLyrics,
                    mediaMetadata = metadata,
                    playerArtworkColors = gradientColors,
                    backdropAnimationTime = sharedBackdropAnimationTime,
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
                    onLyricsCloseSettled = {
                        if (!showInlineLyrics) {
                            freezeBackdropAfterLyricsSettles = false
                        }
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
 * Lyrics opens as one rigid full-screen sheet that rises from the bottom. The detailed backdrop is
 * counter-translated inside that moving sheet so its light/gradient coordinates stay fixed to the
 * display, while the foreground rides with the sheet. This keeps the physical "canvas" motion
 * without reintroducing the colour sweep that moving procedural backgrounds used to cause.
 */
private const val LyricsTravelMillis = 590
private const val LyricsCloseForegroundMillis = 160
private const val LyricsCloseSurfaceMillis = 260
private val LyricsCloseDrop = 36.dp
private const val LyricsFrameWidthInset = 0.028f
private const val LyricsFrameHeightInset = 0.018f
private val LyricsFrameOrigin = TransformOrigin(0.5f, 0f)

/**
 * Softer off the mark than the player's, and a touch longer.
 *
 * The sheet should feel lighter than the thing it covers: the player is a slab being moved, the
 * lyrics are a page being drawn out. Spending even less distance in the first frames is what carries
 * that difference in time as well as in shape.
 */
private val LyricsEasing = CubicBezierEasing(0.38f, 0.04f, 0.22f, 1f)
private val LyricsForegroundAcquireEasing = CubicBezierEasing(0.32f, 0f, 0.26f, 1f)
private val LyricsDissolveEasing = CubicBezierEasing(0.32f, 0f, 0.34f, 1f)
private val LyricsSurfaceReleaseEasing = CubicBezierEasing(0.30f, 0f, 0.20f, 1f)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CapsulePlayerLyricsHost(
    design: CapsulePlayerDesign,
    showLyrics: Boolean,
    mediaMetadata: MediaMetadata,
    playerArtworkColors: List<Color>,
    backdropAnimationTime: State<Long>,
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
    onLyricsCloseSettled: () -> Unit,
    onShowMenu: () -> Unit,
) {
    // Each property keeps its current value on cancellation. A quick reverse therefore starts
    // from the pixels already on screen, rather than switching to another geometry formula.
    val sheetOffset = remember { Animatable(if (showLyrics) 0f else 1f) }
    val sheetOpacity = remember { Animatable(if (showLyrics) 1f else 0f) }
    val foregroundOpacity = remember { Animatable(if (showLyrics) 1f else 0f) }
    val frameRelease = remember { Animatable(if (showLyrics) 0f else 1f) }
    val closeRelease = remember { Animatable(0f) }
    var lyricsLayerMounted by remember { mutableStateOf(showLyrics) }
    var lyricsForegroundMounted by remember { mutableStateOf(showLyrics) }
    var lyricsRuntimeActive by remember { mutableStateOf(showLyrics) }

    DisposableEffect(Unit) {
        // Clearing the current song can remove the host before the transition has completed.
        onDispose { onLyricsCloseSettled() }
    }

    LaunchedEffect(showLyrics) {
        if (showLyrics) {
            if (!lyricsLayerMounted) {
                sheetOffset.snapTo(1f)
                sheetOpacity.snapTo(1f)
                foregroundOpacity.snapTo(0f)
                frameRelease.snapTo(1f)
                closeRelease.snapTo(0f)
            }
            lyricsLayerMounted = true
            lyricsForegroundMounted = true
            lyricsRuntimeActive = false
            coroutineScope {
                launch {
                    sheetOffset.animateTo(0f, tween(LyricsTravelMillis, easing = LyricsEasing))
                }
                launch {
                    frameRelease.animateTo(0f, tween(LyricsTravelMillis, easing = LyricsEasing))
                }
                launch {
                    // These are already at their targets on a normal opening. On reversal they
                    // restore the existing opening continuously from the interrupted close.
                    closeRelease.animateTo(0f, tween(LyricsTravelMillis, easing = LyricsEasing))
                }
                launch {
                    sheetOpacity.animateTo(1f, tween(240, easing = LyricsForegroundAcquireEasing))
                }
                launch {
                    // Centre the list while it is still transparent, then reveal the entire UI.
                    delay(160L)
                    lyricsRuntimeActive = true
                    foregroundOpacity.animateTo(1f, tween(360, easing = LyricsForegroundAcquireEasing))
                }
            }
        } else if (lyricsLayerMounted) {
            lyricsRuntimeActive = false
            // Dissolve the whole UI while the page is stationary. Keep the floor opaque so the
            // departing text/controls never overlap the incoming player's title and controls.
            foregroundOpacity.animateTo(
                0f,
                tween(LyricsCloseForegroundMillis, easing = LyricsDissolveEasing),
            )
            // Remove the invisible list before the surface release. Only a frozen background is
            // animated now: one alpha layer and a small density-independent drop, without shrink.
            lyricsForegroundMounted = false
            coroutineScope {
                launch {
                    closeRelease.animateTo(1f, tween(LyricsCloseSurfaceMillis, easing = LyricsSurfaceReleaseEasing))
                }
                launch {
                    sheetOpacity.animateTo(0f, tween(LyricsCloseSurfaceMillis, easing = LyricsSurfaceReleaseEasing))
                }
            }
            lyricsLayerMounted = false
            onLyricsCloseSettled()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize(),
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
                            if (mediaMetadata.id.startsWith("soundcloud:") &&
                                artistId.startsWith("https://soundcloud.com/")
                            ) {
                                navController.navigate(
                                    "soundcloud/profile?url=" + Uri.encode(artistId)
                                )
                            } else {
                                navController.navigate("artist/$artistId")
                            }
                            playerState.collapseSoft()
                        }
                    },
                    onShowLyrics = onShowLyrics,
                    onMenuClick = onShowMenu,
                    onExpandQueue = {
                        if (!showLyrics) {
                            queueState.expandSoft()
                        }
                    },
                    bottomPadding = 0.dp,
                    open = !playerState.isCollapsed && !playerState.isDismissed,
                    expansionProgress = playerState.rawProgress,
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
                onExpandQueue = {
                    if (!showLyrics) {
                        queueState.expandSoft()
                    }
                },
                onCollapse = playerState::collapseSoft,
                onArtworkClick = onShowLyrics,
                onArtistSelected = { artist ->
                    artist.id?.let { artistId ->
                        onHideLyrics()
                        if (mediaMetadata.id.startsWith("soundcloud:") &&
                            artistId.startsWith("https://soundcloud.com/")
                        ) {
                            navController.navigate(
                                "soundcloud/profile?url=" + Uri.encode(artistId)
                            )
                        } else {
                            navController.navigate("artist/$artistId")
                        }
                        playerState.collapseSoft()
                    }
                },
                onMenuClick = onShowMenu,
                context = LocalContext.current,
                bottomPadding = 0.dp,
                open =
                    !playerState.isCollapsed &&
                        !playerState.isDismissed &&
                        !showLyrics,
                expansionProgress = playerState.rawProgress,
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val fullHeightPx = constraints.maxHeight.toFloat()
            val density = LocalDensity.current
            val systemBars = WindowInsets.systemBarsIgnoringVisibility
            val safeTopPx = systemBars.getTop(density).toFloat()
            val safeBottomPx = systemBars.getBottom(density).toFloat()
            val closeDropPx = with(density) { LyricsCloseDrop.toPx() }
            // Read animation values only inside the render layer. The list is not recomposed or
            // measured on every transition frame, and the rounded outline needs no blur/mask pass.
            val lyricsFrame = Modifier.graphicsLayer {
                val release = frameRelease.value
                translationY = sheetOffset.value * fullHeightPx + closeRelease.value * closeDropPx
                scaleX = 1f - LyricsFrameWidthInset * release
                scaleY = 1f - LyricsFrameHeightInset * release
                transformOrigin = LyricsFrameOrigin
                shape = RoundedCornerShape(28.dp * release + 20.dp * closeRelease.value)
                clip = true
            }

            if (lyricsLayerMounted) {
                Box(
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = sheetOpacity.value
                        // Auto applies alpha to the composited page, rather than to overlapping
                        // draw commands. Its temporary buffer is only needed while alpha < 1.
                        compositingStrategy = CompositingStrategy.Auto
                    },
                ) {
                    // Background and foreground share the rounded frame. The background itself
                    // keeps its colours fixed to the display through both transitions.
                    Box(modifier = Modifier.fillMaxSize().then(lyricsFrame)) {
                        Box(
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                val release = frameRelease.value
                                scaleX = 1f / (1f - LyricsFrameWidthInset * release)
                                scaleY = 1f / (1f - LyricsFrameHeightInset * release)
                                transformOrigin = LyricsFrameOrigin
                                translationY = -(sheetOffset.value * fullHeightPx +
                                    closeRelease.value * closeDropPx) * scaleY
                            },
                        ) {
                            CapsuleLyricsBackdropLayer(
                                mediaMetadata = mediaMetadata,
                                playerArtworkColors = playerArtworkColors,
                                backdropAnimationTime = backdropAnimationTime,
                                isVisible = showLyrics,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    // This clip belongs to the stationary viewport, not to the moving page. It
                    // prevents travelling controls from painting behind system navigation buttons.
                    if (lyricsForegroundMounted) Box(
                        modifier = Modifier.fillMaxSize().drawWithContent {
                            clipRect(top = safeTopPx, bottom = size.height - safeBottomPx) {
                                this@drawWithContent.drawContent()
                            }
                        },
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().then(lyricsFrame).graphicsLayer {
                                alpha = foregroundOpacity.value
                                compositingStrategy = CompositingStrategy.Auto
                            },
                        ) {
                            LyricsScreen(
                                mediaMetadata = mediaMetadata,
                                onBackClick = onHideLyrics,
                                playerArtworkColors = playerArtworkColors,
                                backdropAnimationTime = backdropAnimationTime,
                                drawBackdrop = false,
                                isVisible = lyricsRuntimeActive,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
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
