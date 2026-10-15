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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.navigation.NavController
import com.kyant.backdrop.Backdrop
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.constants.CapsulePlayerDesign
import com.nikhil.yt.constants.CapsulePlayerDesignKey
import com.nikhil.yt.constants.DarkModeKey
import com.nikhil.yt.constants.MiniPlayerHeight
import com.nikhil.yt.constants.MiniPlayerBackgroundStyle
import com.nikhil.yt.constants.MiniPlayerBackgroundStyleKey
import com.nikhil.yt.constants.PlayerBackgroundStyle
import com.nikhil.yt.constants.PlayerBackgroundStyleKey
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.models.mergeArtistCredits
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.ui.component.BottomSheet
import com.nikhil.yt.ui.component.BottomSheetState
import com.nikhil.yt.ui.component.PlayerContentHandoffPoint
import com.nikhil.yt.ui.component.playerHandoffSurfaceAlpha
import com.nikhil.yt.ui.component.playerBackdropContentAlpha
import com.nikhil.yt.ui.component.LocalBottomSheetPageState
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.MenuPresentation
import com.nikhil.yt.ui.component.rememberBottomSheetState
import com.nikhil.yt.ui.menu.PlayerMenu
import com.nikhil.yt.ui.screens.settings.DarkMode
import com.nikhil.yt.ui.utils.ShowMediaInfo
import com.nikhil.yt.ui.theme.CapsuleBottomBarEnabledKey
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

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
    miniPlayerBackdrop: Backdrop? = null,
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    var showInlineLyrics by rememberSaveable {
        mutableStateOf(false)
    }

    val playerDesign by rememberEnumPreference(CapsulePlayerDesignKey, CapsulePlayerDesign.SUPER)
    val capsuleDock by rememberPreference(CapsuleBottomBarEnabledKey, false)
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
    val miniPlayerBackground by
        rememberEnumPreference(
            MiniPlayerBackgroundStyleKey,
            MiniPlayerBackgroundStyle.CAPSULE_STAR,
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
    val videoPlaybackState by playerConnection.service.videoPlaybackState.collectAsState()
    val positionDiscontinuityRevision by playerConnection.positionDiscontinuityRevision.collectAsState()
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
            val localArtistCredits = currentSong?.toMediaMetadata()?.artists.orEmpty()
            val enrichedArtists =
                mergeArtistCredits(
                    primary = metadata.artists,
                    secondary = localArtistCredits,
                )

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
        mutableLongStateOf(playerConnection.service.capsuleTimelinePlayer.currentPosition.coerceAtLeast(0L))
    }
    var duration by remember(mediaMetadata?.id) {
        mutableLongStateOf(playerConnection.service.capsuleTimelinePlayer.duration)
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
        videoPlaybackState.presentingVideo,
        videoPlaybackState.videoId,
        positionDiscontinuityRevision,
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
            position = playerConnection.service.capsuleTimelinePlayer.currentPosition.coerceAtLeast(0L)
            duration =
                playerConnection.service.capsuleTimelinePlayer.duration
                    .takeIf { it > 0L && it != C.TIME_UNSET }
                    ?: C.TIME_UNSET
            if (!isPlaying) break
            delay(
                when {
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
            enrichedMetadata != null &&
            (miniPlayerBackground != MiniPlayerBackgroundStyle.THEME ||
                (effectivePlayerDesign != CapsulePlayerDesign.IMMERSIVE &&
                    playerBackground != PlayerBackgroundStyle.DEFAULT))
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
            effectivePlayerDesign != CapsulePlayerDesign.IMMERSIVE &&
            state.shouldLayerAboveCollapsedChrome &&
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

    // Parent Player owns BACK as one unit. Lyrics/Queue may choose what BACK does only while
    // the Player's semantic target is Expanded.
    BackHandler(enabled = state.isExpandedOrExpanding) {
        when {
            showInlineLyrics && state.isExpandedOrExpanding -> showInlineLyrics = false
            queueSheetState.isExpandedOrExpanding ->
                queueSheetState.collapseSoft()
            state.isExpandedOrExpanding ->
                state.collapseSoft()
        }
    }

    BottomSheet(
        state = state,
        modifier = modifier,
        backgroundColor = playerSurfaceColor(useBlackBackground),
        gesturesEnabled = !showInlineLyrics && !freezeBackdropAfterLyricsSettles,
        // Restored original Capsule gesture: pulling the compact player below its dock dismisses
        // the playback surface and stopAndClearPlayback() clears the active song and queue.
        allowSwipeDismiss = true,
        dismissOnlyFromCollapsed = true,
        backHandlerEnabled = false,
        collapsedContentHeight = MiniPlayerHeight,
        // Match CapsuleMiniPlayer's real geometry so the full surface grows out of the card
        // instead of appearing as an unrelated full-width page behind it.
        collapsedHorizontalInset = if (capsuleDock) 10.dp else 12.dp,
        collapsedTopCornerRadius = if (capsuleDock) 24.dp else 14.dp,
        surfaceAlpha = ::playerHandoffSurfaceAlpha,
        onDismiss = {
            playerConnection.service.stopAndClearPlayback()
        },
        collapsedContent = {
            val miniVisible = onScreen && state.compactSurfaceVisible
            MiniPlayer(
                // Avoid propagating the full-player's 300ms progress clock into a subtree that is
                // completely covered. As soon as the collapse animation leaves the expanded
                // anchor, live values are restored before the mini-player is visibly exposed.
                position = if (miniVisible) position else 0L,
                duration = if (miniVisible) duration else 0L,
                pureBlack = pureBlack,
                visible = miniVisible,
                glassBackdrop = miniPlayerBackdrop,
                // BottomSheet fades compact artwork + controls as one UI tree.
                foregroundAlpha = { 1f },
                foregroundInteractive = state.compactForegroundAcceptsInput,
            )
        },
        backgroundContent = {
            // Carry the compact card's actual palette through the empty part of the sheet.
            // The chosen full-player backdrop then replaces it as the full UI arrives, instead
            // of exposing the neutral Surface for a quarter of the opening travel.
            if (state.visualProgress < 0.5f) {
                CapsuleCompactSurfaceBackground(
                    style = miniPlayerBackground,
                    pureBlack = pureBlack,
                    colors = gradientColors,
                    modifier = Modifier.fillMaxSize(),
                    animated = false,
                )
            }
            if (effectivePlayerDesign != CapsulePlayerDesign.IMMERSIVE &&
                state.visualProgress > PlayerContentHandoffPoint
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = playerBackdropContentAlpha(state.visualProgress)
                        }
                        .background(playerSurfaceColor(useBlackBackground)),
                ) {
                    PlayerBackground(
                        playerBackground = playerBackground,
                        gradientColors = gradientColors,
                        animated = backdropTimelineRunning,
                        sharedAnimationTime = sharedBackdropAnimationTime,
                    )
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            enrichedMetadata?.let { metadata ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
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
                            playerConnection.service.seekToCapsuleTimeline(it)
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
                    // BottomSheet owns the one transition alpha for every player design.
                    // Immersive keeps this hook for its internal layout, but a second fade here
                    // would square the opacity and desynchronise it from Light/Cosmo.
                    transitionForegroundAlpha = { 1f },
                    onShowMenu = {
                        menuState.show(presentation = MenuPresentation.CAPSULE_PLAYER) {
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
// 60 Hz gets the same physical journey as 120 Hz: continuous spring velocity,
// no high-refresh-only per-frame increments and no independently staged axes.
// A nearly-critical spring avoids jittering text at the end of the sheet travel.
private const val LyricsOpenSpringStiffness = 116f
private const val LyricsCloseSpringStiffness = 208f
private const val LyricsForegroundOpenDelayMillis = 76
private const val LyricsForegroundOpenFadeMillis = 390
private const val LyricsForegroundCloseFadeMillis = 250
private val LyricsFrameCornerRadius = 32.dp

/**
 * Two nearly critically damped spring profiles share one physical frame and retain velocity
 * on interruption. Closing is quicker and its controls dissolve before the final sheet travel;
 * at 60 Hz neither geometry nor timing depends on a high-refresh frame count.
 */
private val LyricsForegroundAcquireEasing = CubicBezierEasing(0.25f, 0.08f, 0.25f, 1f)
// An early dissolve prevents bright text/slider streaks during the faster sheet exit.
private val LyricsForegroundReleaseEasing = CubicBezierEasing(0.28f, 0f, 0.24f, 1f)

/** A bounded leading-edge lag, exactly zero at both resting endpoints. */
internal fun capsuleLyricsEdgeRecoil(body: Float, lead: Float): Float {
    val progress = body.coerceIn(0f, 1f)
    val envelope = sin((PI * progress).toFloat()).coerceAtLeast(0f)
    return ((lead - body) * envelope).coerceIn(-0.26f, 0.26f)
}

/**
 * A real, visible depth cue while the Lyrics canvas travels, rather than a
 * sharper drop with a barely noticeable lip wobble. Peaks at mid-flight and
 * becomes precisely 0 at either rest anchor so the full-screen artwork never
 * shows a hairline or distorted edge while stationary.
 *
 * Full layout, touch targets, text and gradient are still composed ONCE at
 * natural size. Only their already-existing parent RenderNodes are scaled.
 */
internal fun capsuleLyricsRetreat(body: Float, lead: Float): Float {
    val p = body.coerceIn(0f, 1f)
    val midFlight = sin((PI * p).toFloat()).coerceAtLeast(0f)
    val lag = kotlin.math.abs(capsuleLyricsEdgeRecoil(body, lead))
    return (midFlight * 0.037f + lag * 0.017f).coerceIn(0f, 0.043f)
}

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
    transitionForegroundAlpha: () -> Float,
    onShowMenu: () -> Unit,
) {
    // Each property keeps its current value on cancellation. A quick reverse therefore starts
    // from the pixels already on screen, rather than switching to another geometry formula.
    val sheetOffset = remember { Animatable(if (showLyrics) 0f else 1f) }
    // A faster edge spring runs alongside the body: the small velocity gap
    // makes the top lip lead and settle like the accepted VIDEO card without
    // stretching/blur-rendering the lyrics glyphs or moving the gradient.
    val sheetEdge = remember { Animatable(if (showLyrics) 0f else 1f) }
    val foregroundOpacity = remember { Animatable(if (showLyrics) 1f else 0f) }
    // The outgoing player stays fully present while Lyrics travels across it. Once the page has
    // covered the last pixel, its lyric row and decorative orbit can sleep until closing starts.
    val playerMotionActive by remember(showLyrics) {
        derivedStateOf { !showLyrics || sheetOffset.value > 0.001f }
    }
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
                sheetEdge.snapTo(1f)
                foregroundOpacity.snapTo(0f)
            }
            lyricsLayerMounted = true
            lyricsForegroundMounted = true
            lyricsRuntimeActive = false
            coroutineScope {
                launch {
                    sheetOffset.animateTo(
                        0f,
                        spring(dampingRatio = 0.93f, stiffness = LyricsOpenSpringStiffness,
                            visibilityThreshold = 0.001f),
                    )
                }
                launch {
                    sheetEdge.animateTo(
                        0f,
                        spring(dampingRatio = 0.87f, stiffness = 165f, visibilityThreshold = 0.001f),
                    )
                }
                launch {
                    // Centre the list while it is still transparent, then reveal the entire UI.
                    delay(LyricsForegroundOpenDelayMillis.toLong())
                    lyricsRuntimeActive = true
                    foregroundOpacity.animateTo(1f, tween(LyricsForegroundOpenFadeMillis, easing = LyricsForegroundAcquireEasing))
                }
            }
        } else if (lyricsLayerMounted) {
            lyricsRuntimeActive = false
            // Reversing starts from the currently drawn pixels, including an interrupted opening.
            coroutineScope {
                launch {
                    sheetOffset.animateTo(
                        1f,
                        spring(dampingRatio = 0.95f, stiffness = LyricsCloseSpringStiffness,
                            visibilityThreshold = 0.001f),
                    )
                }
                launch {
                    sheetEdge.animateTo(
                        1f,
                        spring(dampingRatio = 0.91f, stiffness = 305f, visibilityThreshold = 0.001f),
                    )
                }
                launch {
                    foregroundOpacity.animateTo(
                        0f,
                        tween(
                            LyricsForegroundCloseFadeMillis,
                            easing = LyricsForegroundReleaseEasing,
                        ),
                    )
                    lyricsForegroundMounted = false
                }
            }
            lyricsLayerMounted = false
            onLyricsCloseSettled()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Both surfaces consume one sample of the player's actual visible cover crop.
        // Hoisting it also avoids a second decode/extraction when Lyrics opens.
        val onScreen = appIsOnScreen()
        val immersiveArtworkTone =
            if (design == CapsulePlayerDesign.IMMERSIVE) {
                rememberImmersiveEdgeColor(
                    mediaMetadata = mediaMetadata,
                    enabled = onScreen && !playerState.isDismissed,
                    visibleArtworkAspectRatio =
                        maxWidth.value / immersiveArtworkHeight(maxHeight).value.coerceAtLeast(1f),
                )
            } else {
                null
            }
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
                    artworkTone = requireNotNull(immersiveArtworkTone),
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
                    controlsActive = playerMotionActive,
                    transitionForegroundAlpha = transitionForegroundAlpha,
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
                bottomPadding = 0.dp,
                motionActive = playerMotionActive,
                open =
                    !playerState.isCollapsed &&
                        !playerState.isDismissed,
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val fullHeightPx = constraints.maxHeight.toFloat()
            val density = LocalDensity.current
            val systemBars = WindowInsets.systemBarsIgnoringVisibility
            val safeTopPx = systemBars.getTop(density).toFloat()
            val safeBottomPx = systemBars.getBottom(density).toFloat()
            // A single travel value drives the frame and its corners. Avoid independently animated
            // scale, dimming and mask layers: their work is especially visible at 60 Hz.
            val lyricsFrame = Modifier.graphicsLayer {
                val travel = sheetOffset.value
                val recoil = capsuleLyricsEdgeRecoil(travel, sheetEdge.value)
                val retreat = capsuleLyricsRetreat(travel, sheetEdge.value)
                // The leading edge is the jelly, the whole page feels like
                // a heavy sheet receding into the distance. No high-Hz steps
                // and no separate text re-layout: RenderNode work only.
                translationY = travel * fullHeightPx + recoil * with(density) { 20.dp.toPx() }
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                scaleX = 1f - retreat
                scaleY = 1f - retreat * 0.62f
                shape = RoundedCornerShape(
                    LyricsFrameCornerRadius * travel.coerceIn(0f, 1f) +
                        8.dp * kotlin.math.abs(recoil)
                )
                clip = travel > 0f || kotlin.math.abs(recoil) > 0.001f
            }

            if (lyricsLayerMounted) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Background and foreground share the rounded frame. The background itself
                    // keeps its colours fixed to the display through both transitions.
                    Box(modifier = Modifier.fillMaxSize().then(lyricsFrame)) {
                        Box(
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                val travel = sheetOffset.value
                                translationY = -travel * fullHeightPx -
                                    capsuleLyricsEdgeRecoil(travel, sheetEdge.value) * with(density) { 20.dp.toPx() }
                            },
                        ) {
                            CapsuleLyricsBackdropLayer(
                                mediaMetadata = mediaMetadata,
                                immersiveArtworkTone = immersiveArtworkTone,
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
                                backHandlerEnabled =
                                    showLyrics && playerState.isExpandedOrExpanding,
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
