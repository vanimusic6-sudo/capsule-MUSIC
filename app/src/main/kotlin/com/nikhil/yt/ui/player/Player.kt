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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.isActive

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
    var lyricsHasOpened by remember {
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
            lyricsHasOpened = true
            freezeBackdropAfterLyricsSettles = true
        } else if (lyricsHasOpened) {
            /*
             * Keep that same frozen frame through close as well. Resume only after Lyrics is fully
             * gone, so artwork/background colour cannot drift underneath a translucent hand-off.
             */
            freezeBackdropAfterLyricsSettles = true
            delay(LyricsCloseMillis.toLong())
            freezeBackdropAfterLyricsSettles = false
        } else {
            // Initial composition is not a Lyrics close transition.
            freezeBackdropAfterLyricsSettles = false
        }
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
private const val LyricsTravelMillis = 540
private const val LyricsCloseMillis = 570

/**
 * Softer off the mark than the player's, and a touch longer.
 *
 * The sheet should feel lighter than the thing it covers: the player is a slab being moved, the
 * lyrics are a page being drawn out. Spending even less distance in the first frames is what carries
 * that difference in time as well as in shape.
 */
private val LyricsEasing = CubicBezierEasing(0.34f, 0.02f, 0.20f, 1f)
private val LyricsCloseEasing = CubicBezierEasing(0.28f, 0.04f, 0.22f, 1f)
private val LyricsForegroundAcquireEasing = CubicBezierEasing(0.24f, 0f, 0.18f, 1f)
private val LyricsBackdropAcquireEasing = CubicBezierEasing(0.22f, 0f, 0.20f, 1f)
private val LyricsForegroundReleaseEasing = CubicBezierEasing(0.24f, 0f, 0.18f, 1f)
private val LyricsVeilReleaseEasing = CubicBezierEasing(0.20f, 0f, 0.22f, 1f)

@Composable
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
    onShowMenu: () -> Unit,
) {
    val lyricsMotion = remember {
        Animatable(if (showLyrics) 1f else 0f)
    }
    var lyricsLayerMounted by remember {
        mutableStateOf(showLyrics)
    }
    var lyricsForegroundMounted by remember {
        mutableStateOf(showLyrics)
    }
    var lyricsRuntimeActive by remember {
        mutableStateOf(showLyrics)
    }

    /*
     * Keep the heavy Lyrics subtree alive only while it can actually contribute pixels.
     *
     * Opening starts with the cheap backdrop alone. The text/list subtree mounts shortly before its
     * acquire fade becomes visible, so parsing/layout/collectors do not compete with the first
     * transition frames. Closing stops its runtime work immediately and unmounts it as soon as the
     * foreground release has visually reached zero, while the cheap colour tail may continue.
     */
    LaunchedEffect(showLyrics) {
        if (showLyrics) {
            /*
             * Compose the foreground immediately while its alpha is still zero, but keep all
             * periodic Lyrics work asleep until the acquire fade is about to become visible. This
             * avoids a text-layout spike landing mid-transition without paying for clocks nobody
             * can see.
             */
            lyricsForegroundMounted = true
            lyricsRuntimeActive = false
            delay(120L)
            lyricsRuntimeActive = true
        } else {
            lyricsRuntimeActive = false
            // Runtime stops immediately, but keep the already-rendered foreground mounted long
            // enough for its low-alpha travel tail to remain visually continuous.
            delay(410L)
            lyricsForegroundMounted = false
        }
    }

    /*
     * Mount/unmount only at the ends of the transition. The animated Float is consumed by
     * graphicsLayer/draw below, so the player/lyrics subtrees are not recomposed every frame.
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
            animationSpec =
                tween(
                    durationMillis =
                        if (showLyrics) {
                            LyricsTravelMillis
                        } else {
                            LyricsCloseMillis
                        },
                    easing =
                        if (showLyrics) {
                            LyricsEasing
                        } else {
                            LyricsCloseEasing
                        },
                ),
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
                        if (design == CapsulePlayerDesign.IMMERSIVE) {
                            // Full-bleed artwork cannot tolerate a sub-pixel shrink: keep the
                            // underlying player completely stable during the Lyrics transition.
                            translationY = 0f
                            scaleX = 1f
                            scaleY = 1f
                        } else {
                            translationY = -2.75f * reaction
                            scaleX = 1f - 0.00070f * reaction
                            scaleY = 1f - 0.00100f * reaction
                        }

                        // The player underneath never dissolves. Only the Lyrics layer fades out
                        // while closing.
                        alpha = 1f
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

            if (lyricsLayerMounted) {
                /*
                 * OPENING: one real sheet moves from the bottom to the top.
                 *
                 * Earlier versions kept the backdrop fixed and merely revealed it with a DstIn
                 * mask while the foreground travelled independently. Geometrically that reads as a
                 * patch growing from an attachment point, not as one physical surface entering the
                 * screen. It also required a full-screen offscreen buffer on every opening frame.
                 *
                 * The outer layer below is the sheet itself. While opening it translates as one
                 * rigid rectangle and clips its children to its own bounds. The backdrop inside is
                 * counter-translated by the exact opposite amount so gradients/stars stay in fixed
                 * screen coordinates and never sweep or change colour. The foreground is NOT
                 * counter-translated, so it rides with the sheet exactly like ink on a page.
                 *
                 * CLOSING intentionally remains the separate translucent hand-off we already tuned:
                 * outer translation is zero, backdrop becomes a veil, foreground travels down.
                 */
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val travelled =
                                    lyricsMotion.value
                                        .coerceIn(0f, 1f)
                                if (showLyrics && travelled < 0.999f) {
                                    translationY =
                                        ((1f - travelled) * fullHeightPx)
                                            .coerceAtLeast(0f)
                                    clip = true
                                } else {
                                    translationY = 0f
                                    clip = false
                                }
                            },
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val travelled =
                                        lyricsMotion.value
                                            .coerceIn(0f, 1f)

                                    if (showLyrics) {
                                        /*
                                         * Counter-motion keeps the structured backdrop visually
                                         * pinned to the display even though its containing sheet is
                                         * physically moving upward.
                                         */
                                        translationY =
                                            -((1f - travelled) * fullHeightPx)
                                                .coerceAtLeast(0f)

                                        /*
                                         * A physical sheet should read as material as soon as it
                                         * enters. Keep it almost opaque from the first visible
                                         * pixels; only a tiny acquire softens the initial contact.
                                         */
                                        val acquire =
                                            LyricsBackdropAcquireEasing.transform(
                                                (travelled / 0.78f)
                                                    .coerceIn(0f, 1f),
                                            )
                                        alpha =
                                            0.94f +
                                                0.06f * acquire
                                    } else {
                                        translationY = 0f

                                        val closeProgress =
                                            (1f - travelled)
                                                .coerceIn(0f, 1f)
                                        val releaseProgress =
                                            LyricsVeilReleaseEasing.transform(
                                                (closeProgress / 0.34f)
                                                    .coerceIn(0f, 1f),
                                            )
                                        val veilAlpha =
                                            1f +
                                                (0.18f - 1f) *
                                                    releaseProgress
                                        val tailProgress =
                                            LyricsVeilReleaseEasing.transform(
                                                ((closeProgress - 0.34f) / 0.66f)
                                                    .coerceIn(0f, 1f),
                                            )
                                        alpha =
                                            veilAlpha *
                                                (1f - tailProgress)
                                    }

                                    /*
                                     * No DstIn and no full-screen temporary texture. The backdrop
                                     * is one drawing subtree, so in-place alpha modulation is enough
                                     * for both the tiny opening acquire and the closing veil.
                                     */
                                    compositingStrategy =
                                        if (alpha > 0.001f && alpha < 0.999f) {
                                            CompositingStrategy.ModulateAlpha
                                        } else {
                                            CompositingStrategy.Auto
                                        }
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

                    /*
                     * The whole UI rides the same opening sheet. There is no stretch and no top
                     * transform origin anymore: those were the cues that made the panel look as if
                     * it was attached to the upper layer rather than being one continuous canvas.
                     *
                     * On close the outer sheet is stationary and this foreground alone travels
                     * downward through the already-tuned two-stage release.
                     */
                    if (lyricsForegroundMounted) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val travelled =
                                            lyricsMotion.value
                                                .coerceIn(0f, 1f)

                                        translationY =
                                            if (showLyrics) {
                                                0f
                                            } else {
                                                ((1f - travelled) * fullHeightPx)
                                                    .coerceAtLeast(0f)
                                            }
                                        scaleX = 1f
                                        scaleY = 1f

                                        alpha =
                                            if (showLyrics) {
                                                val acquireProgress =
                                                    ((travelled - 0.16f) / 0.78f)
                                                        .coerceIn(0f, 1f)
                                                LyricsForegroundAcquireEasing.transform(
                                                    acquireProgress,
                                                )
                                            } else {
                                                val closeProgress =
                                                    (1f - travelled)
                                                        .coerceIn(0f, 1f)
                                                val deEmphasis =
                                                    LyricsForegroundReleaseEasing.transform(
                                                        (closeProgress / 0.24f)
                                                            .coerceIn(0f, 1f),
                                                    )
                                                val softBodyAlpha =
                                                    1f +
                                                        (0.24f - 1f) *
                                                            deEmphasis
                                                val tail =
                                                    LyricsForegroundReleaseEasing.transform(
                                                        (
                                                            (closeProgress - 0.24f) /
                                                                0.50f
                                                        ).coerceIn(0f, 1f),
                                                    )
                                                softBodyAlpha *
                                                    (1f - tail)
                                            }

                                        compositingStrategy =
                                            if (alpha > 0.001f && alpha < 0.999f) {
                                                CompositingStrategy.ModulateAlpha
                                            } else {
                                                CompositingStrategy.Auto
                                            }
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
