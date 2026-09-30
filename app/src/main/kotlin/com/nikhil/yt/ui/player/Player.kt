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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
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
import com.nikhil.yt.ui.motion.CapsuleMotion
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
     * Do not change the procedural backdrop's running/frozen mode on the same frame that Lyrics
     * starts travelling. Some effects have phase-dependent luminance, so switching the clock at
     * pointer-up can make the still-visible top of the player look like it flashed brighter even
     * though the palette did not change.
     *
     * Let the existing backdrop continue exactly as it was for the 480 ms travel, then freeze it
     * only after Lyrics has fully covered the player. Closing Lyrics resumes immediately, before
     * the underlying player is revealed.
     */
    var freezeBackdropAfterLyricsSettles by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(showInlineLyrics) {
        if (showInlineLyrics) {
            freezeBackdropAfterLyricsSettles = false
            delay(LyricsTravelMillis.toLong())
            freezeBackdropAfterLyricsSettles = true
        } else {
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
            (!showInlineLyrics || !freezeBackdropAfterLyricsSettles)
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
private const val LyricsTravelMillis = 480
private const val LyricsCloseMillis = 570

/**
 * Softer off the mark than the player's, and a touch longer.
 *
 * The sheet should feel lighter than the thing it covers: the player is a slab being moved, the
 * lyrics are a page being drawn out. Spending even less distance in the first frames is what carries
 * that difference in time as well as in shape.
 */
private val LyricsEasing = CubicBezierEasing(0.42f, 0f, 0.28f, 1f)
private val LyricsCloseEasing = CubicBezierEasing(0.32f, 0.07f, 0.16f, 1f)

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
                 * The backdrop is screen-anchored and revealed by a moving clip. It never travels
                 * or stretches with the page, so a vertical gradient/glow/starfield keeps the same
                 * screen coordinates as the player underneath. A solid theme looked fine before
                 * because every pixel is identical; structured backgrounds exposed this bug as a
                 * bright sweep during the transition.
                 */
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                /*
                                 * Opening keeps the Lyrics backdrop fully opaque. Closing is
                                 * different: dissolve the Lyrics surface from the first frame and
                                 * reach zero exactly as it leaves the screen.
                                 */
                                val travelled = lyricsMotion.value.coerceIn(0f, 1f)
                                alpha =
                                    if (showLyrics) {
                                        1f
                                    } else {
                                        /*
                                         * Keep the colour surface visually solid for most of the
                                         * downward travel. Fading it from frame one made the sheet
                                         * read as if it dissolved halfway down instead of actually
                                         * leaving the screen. Only the final ~30% of travel fades.
                                         */
                                        val baseAlpha =
                                            CapsuleMotion.smooth(
                                                (travelled / 0.30f)
                                                    .coerceIn(0f, 1f),
                                            )
                                        val tailFade =
                                            (travelled / 0.18f)
                                                .coerceIn(0f, 1f)
                                        /*
                                         * Stronger final transparency, applied to the whole
                                         * remaining sheet uniformly. Squaring only this tail
                                         * factor leaves the travel/reveal geometry untouched.
                                         */
                                        baseAlpha * tailFade * tailFade
                                    }

                                /*
                                 * DstIn needs an offscreen buffer only while the reveal edge is
                                 * actually moving. Once Lyrics is fully open the backdrop is static
                                 * and opaque, so keeping a full-screen offscreen layer alive wastes
                                 * GPU bandwidth and memory for no visual benefit.
                                 */
                                compositingStrategy =
                                    if (travelled < 0.999f) {
                                        CompositingStrategy.Offscreen
                                    } else {
                                        CompositingStrategy.Auto
                                    }
                            }
                            .drawWithContent {
                                val travelled = lyricsMotion.value.coerceIn(0f, 1f)
                                if (travelled <= 0.001f) {
                                    return@drawWithContent
                                }

                                drawContent()

                                if (travelled < 0.999f) {
                                    /*
                                     * A hard clip produced the thin horizontal "knife edge" seen
                                     * while closing Lyrics. Feather only the moving boundary; the
                                     * rest of the backdrop stays fully opaque and screen-anchored.
                                     */
                                    val revealTop =
                                        ((1f - travelled) * size.height)
                                            .coerceIn(0f, size.height)
                                    /*
                                     * A fixed 44dp feather becomes a huge part of the sheet when
                                     * only a small strip is left on screen. That is why the lower
                                     * part looked transparent while the upper part was still
                                     * travelling. Keep the feather proportional to the remaining
                                     * visible height, capped at the old maximum.
                                     */
                                    val visibleHeightPx =
                                        (size.height * travelled)
                                            .coerceAtLeast(0f)
                                    val featherPx =
                                        minOf(
                                            44.dp.toPx(),
                                            visibleHeightPx * 0.075f,
                                        ).coerceAtLeast(1f)
                                    val transparentEnd =
                                        ((revealTop - featherPx) / size.height)
                                            .coerceIn(0f, 1f)
                                    val opaqueStart =
                                        ((revealTop + featherPx) / size.height)
                                            .coerceIn(transparentEnd, 1f)

                                    drawRect(
                                        brush =
                                            Brush.verticalGradient(
                                                colorStops =
                                                    arrayOf(
                                                        0f to Color.Transparent,
                                                        transparentEnd to Color.Transparent,
                                                        opaqueStart to Color.Black,
                                                        1f to Color.Black,
                                                    ),
                                            ),
                                        blendMode = BlendMode.DstIn,
                                    )
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
                 * Only Lyrics controls/text travel. The background above is fixed to the display
                 * and simply revealed underneath this foreground, so the two screens never expose
                 * one another through a transparent page and the gradient itself never moves.
                 */
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val travelled = lyricsMotion.value.coerceIn(0f, 1f)
                                translationY =
                                    ((1f - travelled) * fullHeightPx).coerceAtLeast(0f)

                                val opening =
                                    CapsuleMotion.approach(
                                        progress = travelled,
                                        window = LyricsOpenWindow,
                                    )
                                val remaining = 1f - opening
                                scaleX = 1f
                                scaleY = 1f + LyricsUnrollStretch * remaining

                                /*
                                 * The backdrop may stay faintly visible into the last pixels of
                                 * travel, but bright lyric glyphs/shadows must not float over an
                                 * already-transparent sheet. Fade the entire foreground as one
                                 * offscreen layer and finish that fade slightly before travel ends.
                                 */
                                alpha =
                                    if (showLyrics) {
                                        1f
                                    } else {
                                        /*
                                         * Close in two visual phases. White UI is the highest
                                         * contrast thing on the screen, so remove it almost
                                         * immediately; the remaining travel then belongs only to
                                         * the quiet gradient surface underneath.
                                         *
                                         * travelled runs 1 -> 0 while closing. This window fades
                                         * the entire foreground during roughly the first quarter
                                         * of the close and keeps it fully gone afterwards.
                                         */
                                        CapsuleMotion.smooth(
                                            ((travelled - 0.58f) / 0.42f)
                                                .coerceIn(0f, 1f),
                                        )
                                    }
                                /*
                                 * The foreground needs an offscreen layer only while it is visibly
                                 * blending. Once the early fade has reached zero, stop allocating a
                                 * full-screen buffer for content the GPU cannot see.
                                 */
                                compositingStrategy =
                                    if (!showLyrics && alpha > 0.001f && alpha < 0.999f) {
                                        CompositingStrategy.Offscreen
                                    } else {
                                        CompositingStrategy.Auto
                                    }
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            },
                ) {
                    LyricsScreen(
                        mediaMetadata = mediaMetadata,
                        onBackClick = onHideLyrics,
                        playerArtworkColors = playerArtworkColors,
                        backdropAnimationTime = backdropAnimationTime,
                        drawBackdrop = false,
                        isVisible = showLyrics,
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
