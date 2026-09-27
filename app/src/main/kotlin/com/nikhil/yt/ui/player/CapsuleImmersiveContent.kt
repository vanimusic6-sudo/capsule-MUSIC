/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsuleImmersiveAvOrderKey
import com.nikhil.yt.constants.CapsuleImmersiveCanvasPositionsKey
import com.nikhil.yt.constants.CapsuleImmersiveEditEnabledKey
import com.nikhil.yt.constants.CapsuleImmersiveLayoutOrderKey
import com.nikhil.yt.constants.CapsuleImmersiveMetadataOrderKey
import com.nikhil.yt.constants.CapsuleImmersiveModeOrderKey
import com.nikhil.yt.constants.CapsuleImmersiveTransportOrderKey
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.extensions.toggleRepeatMode
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.playback.PlayerConnection
import com.nikhil.yt.playback.video.CapsulePlaybackMode
import com.nikhil.yt.playback.video.CapsuleVideoPhase
import com.nikhil.yt.ui.component.CapsuleFavoriteColors
import com.nikhil.yt.ui.component.CapsuleFavoriteIcon
import com.nikhil.yt.utils.makeTimeString
import com.nikhil.yt.utils.rememberPreference

/**
 * The outgoing fade is a SINGLE operation for a displayed frame, regardless of how
 * many newer songs arrive while it is running. Explicitly avoid the current song's
 * readiness here: an unprepared next cover must not delay or restart the fade.
 */
internal fun shouldFadeOutImmersiveFrame(
    displayedKey: String?,
    selectedKey: String,
    visible: Boolean,
): Boolean = displayedKey != null && (!visible || displayedKey != selectedKey)

/** The outgoing gradient survives a media-item change until it has faded to neutral. */
private data class ImmersiveGradientFrame(
    val key: String,
    val edge: Color,
    val floor: Color,
    val imageUrl: String,
    val landscape: Boolean,
    val bottomTexture: Float,
)

internal data class ImmersiveFadeProfile(
    val start: Float,
    val firstStop: Float,
    val firstAlpha: Float,
    val secondStop: Float,
    val secondAlpha: Float,
    val thirdStop: Float,
    val thirdAlpha: Float,
)

internal fun immersiveFadeProfile(
    bottomTexture: Float,
    landscape: Boolean,
): ImmersiveFadeProfile {
    val texture = bottomTexture.coerceIn(0f, 1f)
    val hard = texture * texture
    val extreme = hard * texture

    return if (landscape) {
        ImmersiveFadeProfile(
            start = 0.34f - 0.14f * texture - 0.06f * hard - 0.02f * extreme,
            firstStop = 0.54f - 0.10f * texture - 0.05f * hard - 0.02f * extreme,
            firstAlpha =
                (0.12f + 0.18f * texture + 0.10f * hard + 0.04f * extreme)
                    .coerceIn(0f, 1f),
            secondStop = 0.72f - 0.08f * texture - 0.05f * hard - 0.02f * extreme,
            secondAlpha =
                (0.42f + 0.26f * texture + 0.12f * hard + 0.05f * extreme)
                    .coerceIn(0f, 1f),
            thirdStop = 0.88f - 0.05f * texture - 0.03f * hard - 0.01f * extreme,
            thirdAlpha =
                (0.76f + 0.20f * texture + 0.06f * hard + 0.02f * extreme)
                    .coerceIn(0f, 1f),
        )
    } else {
        ImmersiveFadeProfile(
            start = ImmersiveFadeStart - 0.22f * texture - 0.08f * hard - 0.03f * extreme,
            firstStop = 0.62f - 0.15f * texture - 0.06f * hard - 0.02f * extreme,
            firstAlpha =
                (0.12f + 0.22f * texture + 0.11f * hard + 0.05f * extreme)
                    .coerceIn(0f, 1f),
            secondStop = 0.76f - 0.12f * texture - 0.06f * hard - 0.02f * extreme,
            secondAlpha =
                (0.42f + 0.30f * texture + 0.13f * hard + 0.05f * extreme)
                    .coerceIn(0f, 1f),
            thirdStop = 0.90f - 0.07f * texture - 0.03f * hard - 0.01f * extreme,
            thirdAlpha =
                (0.76f + 0.22f * texture + 0.07f * hard + 0.02f * extreme)
                    .coerceIn(0f, 1f),
        )
    }
}

/** How much of the sheet the cover takes before it starts to go. */
private const val ImmersiveArtworkFraction = 0.55f

/**
 * Where inside the cover the dissolve begins, as a fraction of its height.
 *
 * A short ramp made a band: at four fifths the cover went from untouched to gone inside a fifth
 * of its height, and the eye reads that as a line drawn across the picture. Back to the longer,
 * softer dissolve, which is what the design was asking for in the first place — the picture does
 * not end anywhere, it stops being there.
 */
private const val ImmersiveFadeStart = 0.44f

/** Smallest cover that still reads as one; largest that still leaves the controls their room. */
private val ImmersiveArtworkMin = 200.dp
private val ImmersiveArtworkMax = 520.dp


/** The grab rail near the bottom, which opens the queue. */
private val ImmersiveQueueRailWidth = 132.dp
private val ImmersiveQueueRailHeight = 5.dp

/** How far the rail sits off the foot of the sheet, rather than against it. */
private val ImmersiveQueueRailLift = 44.dp
private val ImmersiveQueueRailTouchHeight = 34.dp

/**
 * How tall the cover is in a sheet of this height.
 *
 * Clamped at both ends. A very short window must not be handed a cover that leaves no room for
 * the controls, and a very tall one must not be handed a cover that runs past the sheet — and
 * neither end may produce a height that a later layout pass could read as negative.
 */
internal fun immersiveArtworkHeight(available: Dp): Dp =
    (available * ImmersiveArtworkFraction).coerceIn(ImmersiveArtworkMin, ImmersiveArtworkMax)

internal fun immersiveEditorHeight(
    totalHeight: Dp,
    artworkHeight: Dp,
    bottomPadding: Dp,
): Dp {
    // The queue rail's 34dp box is only its touch target. Reserving that whole box made the
    // editor stop well above the visible 5dp rail, clipping the transport capsule and leaving the
    // solver without enough room to perform EDGE/reflow. Stop immediately above the *visible*
    // rail instead: half the hit box reaches its centre, half the rail height reaches its top.
    val visibleRailTopFromBottom =
        bottomPadding +
            ImmersiveQueueRailLift +
            ImmersiveQueueRailTouchHeight / 2f +
            ImmersiveQueueRailHeight / 2f

    return (
        totalHeight -
            artworkHeight -
            visibleRailTopFromBottom
        ).coerceAtLeast(0.dp)
}

/**
 * A player where the cover is the screen.
 *
 * Cosmo and Light both frame the artwork: a card with an edge, sitting in its own reserved space
 * with the controls arranged beneath. This one has no frame. The cover runs the full width from
 * the very top and simply stops being there — the bottom half of it dissolves into the player's
 * own background, so there is no line anywhere saying where the picture ends and the app begins.
 *
 * Everything below that is deliberately familiar. The transport panel is Capsule Light's, reused
 * rather than redrawn, so the two designs cannot drift apart. What differs is what sits above it:
 * the title keeps its own line with lyrics and like as round chips beside it, and the progress bar
 * is a full-width rail rather than a hairline.
 *
 * This screen also declines the player's background styles. Every other design draws a chosen
 * backdrop — nebula, glow, star — behind the artwork, and here that would be a second picture
 * competing with the first. There is one background and the cover makes it: a colour taken from
 * the artwork itself, which the cover then dissolves into.
 *
 * The dissolve is a gradient drawn over the image, not an alpha mask. A mask would need an
 * offscreen layer for the whole cover, and this app has already paid for one of those once — the
 * shimmer's alpha buffer was what made the phone warm.
 *
 * The seam that a first attempt produced is gone by construction rather than by matching. The
 * gradient ends on exactly the same value the page is painted with, because it is the same value:
 * fade and floor are one colour, so there is nothing for a boundary to disagree about. Picking a
 * near-enough colour is what drew the line across the screen the first time.
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
    onMenuClick: () -> Unit,
    onExpandQueue: () -> Unit,
    bottomPadding: Dp,
    open: Boolean = true,
    /**
     * Whether the sheet has arrived, not merely left the bottom.
     *
     * The status bar is hidden on this and nothing else. Hiding it the moment a drag begins
     * changes the window insets while the screens behind are still visible, and everything back
     * there jumps up with the clock. Waiting until the player covers them means the layout
     * behind still shifts, but under a sheet nobody can see through.
     */
    expanded: Boolean = open,
) {
    val onScreen = appIsOnScreen()
    val visible = open && onScreen

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val videoPlaybackState by playerConnection.service.videoPlaybackState.collectAsState()

    val isLoading = playbackState == Player.STATE_BUFFERING

    val isVideo =
        videoPlaybackState.mode == CapsulePlaybackMode.VIDEO &&
            videoPlaybackState.phase == CapsuleVideoPhase.PLAYING

    // Requesting VIDEO is not the same as having a video to display. Keep the complete
    // audio artwork in place throughout RESOLVING and any request-guard backoff. The
    // service deliberately marks PLAYING before replacing the Media3 item; even that
    // is too early for a visible video stage until its FIRST frame is rendered.
    var videoFirstFrameRendered by remember(mediaMetadata.id, videoPlaybackState.videoId) {
        mutableStateOf(false)
    }
    DisposableEffect(playerConnection.player, isVideo, videoPlaybackState.videoId, mediaMetadata.id) {
        if (isVideo) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    videoFirstFrameRendered = true
                }
            }
            playerConnection.player.addListener(listener)
            onDispose { playerConnection.player.removeListener(listener) }
        } else {
            onDispose { }
        }
    }
    // Once a video has rendered its first frame, switch surfaces in one frame. Do not
    // animate AUDIO <-> VIDEO; only the neutral placeholder -> prepared artwork fades.
    val presentingVideo = isVideo && videoFirstFrameRendered

    /*
     * The status bar goes while this screen is up.
     *
     * The clock and the battery belong to the phone, not to the cover, and in a design whose whole
     * point is that the picture runs to the edge of the glass they are the one thing that says
     * otherwise. Transient-by-swipe, so a swipe from the top still brings them back.
     *
     * Restored on the way out rather than on a flag, so it comes back whether the player was
     * closed, the design was changed, or the screen simply left the composition.
     */
    val context = LocalContext.current
    val hideSystemBars = expanded && onScreen
    DisposableEffect(hideSystemBars) {
        val window = context.immersiveWindow()
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (hideSystemBars && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }

    /*
     * One artist opens their page; several ask which was meant. Same rule and the same dialog as
     * every other screen, because a track credited to two people should not behave differently
     * depending on which player design is switched on.
     */
    val navigableArtists = rememberNavigableArtists(mediaMetadata.artists)
    var showArtistPicker by remember { mutableStateOf(false) }

    if (showArtistPicker) {
        CapsuleArtistPickerDialog(
            artists = navigableArtists,
            onDismiss = { showArtistPicker = false },
            onArtistSelected = onArtistSelected,
        )
    }

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerValue by remember { mutableFloatStateOf(30f) }
    val sleepTimerEnabled =
        remember(
            playerConnection.service.sleepTimer.triggerTime,
            playerConnection.service.sleepTimer.pauseWhenSongEnd,
        ) { playerConnection.service.sleepTimer.isActive }

    if (showSleepTimerDialog) {
        CapsuleSleepTimerDialog(
            minutes = sleepTimerValue,
            enabled = true,
            onMinutesChange = { sleepTimerValue = it },
            onConfirm = {
                playerConnection.service.sleepTimer.start(sleepTimerValue.toInt())
                showSleepTimerDialog = false
            },
            onDismiss = { showSleepTimerDialog = false },
        )
    }

    val safeDuration = durationMs.coerceAtLeast(0L)
    val shownPosition = (sliderPosition ?: positionMs).coerceIn(0L, safeDuration)

    val (immersiveEditEnabled, _) =
        rememberPreference(
            CapsuleImmersiveEditEnabledKey,
            defaultValue = false,
        )
    val (immersiveOrderEncoded, onImmersiveOrderEncodedChange) =
        rememberPreference(
            CapsuleImmersiveLayoutOrderKey,
            defaultValue = CapsuleImmersiveBaseOrderEncoded,
        )
    val (immersivePositionsEncoded, onImmersivePositionsEncodedChange) =
        rememberPreference(
            CapsuleImmersiveCanvasPositionsKey,
            defaultValue = CapsuleLightCanvasPositionsBaseEncoded,
        )
    val (immersiveMetadataOrderEncoded, onImmersiveMetadataOrderEncodedChange) =
        rememberPreference(
            CapsuleImmersiveMetadataOrderKey,
            defaultValue = CapsuleLightMetadataBaseOrderEncoded,
        )
    val (immersiveModeOrderEncoded, onImmersiveModeOrderEncodedChange) =
        rememberPreference(
            CapsuleImmersiveModeOrderKey,
            defaultValue = CapsuleLightModeBaseOrderEncoded,
        )
    val (immersiveAvOrderEncoded, onImmersiveAvOrderEncodedChange) =
        rememberPreference(
            CapsuleImmersiveAvOrderKey,
            defaultValue = CapsuleLightAvBaseOrderEncoded,
        )
    val (immersiveTransportOrderEncoded, onImmersiveTransportOrderEncodedChange) =
        rememberPreference(
            CapsuleImmersiveTransportOrderKey,
            defaultValue = CapsuleLightTransportBaseOrderEncoded,
        )

    var immersiveOrder by
        remember(immersiveOrderEncoded) {
            mutableStateOf(decodeCapsuleImmersiveOrder(immersiveOrderEncoded))
        }
    var immersivePositions by
        remember(immersivePositionsEncoded) {
            mutableStateOf(decodeCapsuleLightCanvasPositions(immersivePositionsEncoded))
        }
    var immersiveMetadataOrder by
        remember(immersiveMetadataOrderEncoded) {
            mutableStateOf(
                decodeCapsuleLightMetadataOrder(immersiveMetadataOrderEncoded),
            )
        }
    var immersiveModeOrder by
        remember(immersiveModeOrderEncoded) {
            mutableStateOf(decodeCapsuleLightModeOrder(immersiveModeOrderEncoded))
        }
    var immersiveAvOrder by
        remember(immersiveAvOrderEncoded) {
            mutableStateOf(decodeCapsuleLightAvOrder(immersiveAvOrderEncoded))
        }
    var immersiveTransportOrder by
        remember(immersiveTransportOrderEncoded) {
            mutableStateOf(
                decodeCapsuleLightTransportOrder(immersiveTransportOrderEncoded),
            )
        }
    var immersiveNestedEditActive by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The artwork region stays at the same height for both audio and video.
        // The actual 16:9 video is centred INSIDE this region, exactly as the other
        // Capsule layouts centre their video card inside the available artwork slot.
        // Shrinking the whole region to 16:9 used to glue the video to the top edge
        // and prematurely pull the title/controls upwards.
        val artworkHeight = immersiveArtworkHeight(maxHeight)
        val videoSidePadding = 22.dp
        val videoFrameWidth = (maxWidth - videoSidePadding * 2).coerceAtLeast(120.dp)
        val videoFrameHeight = (videoFrameWidth * (9f / 16f)).coerceAtMost(artworkHeight)
        val videoFrameTop = ((artworkHeight - videoFrameHeight) / 2f).coerceAtLeast(0.dp)
        // Colour is sampled from the EXACT centre crop visible in this viewport, not
        // the full image or another song's palette. This also keeps landscape layouts sane.
        val artworkAspect = maxWidth.value / artworkHeight.value.coerceAtLeast(1f)
        val artworkTone = rememberImmersiveEdgeColor(
            mediaMetadata = mediaMetadata,
            enabled = visible,
            visibleArtworkAspectRatio = artworkAspect,
        )
        val edge = artworkTone.edge
        // Keep the entire fade in the lower background's hue. Never mix in a vibrant
        // foreground accent (a green logo on white paper is not a green background).
        val floor = artworkTone.accent

        // Decode + sample the SAME chosen URL before rendering either its thumbnail or
        // coloured floor. AsyncImage may need its own larger decoded bitmap, so the palette
        // being ready alone is not proof the actual cover can be drawn yet.
        var coverImageReady by remember(mediaMetadata.id, artworkTone.displayUrl) {
            mutableStateOf(false)
        }
        val coverAndGradientReady = canRevealImmersiveArtwork(artworkTone, coverImageReady)
        // The remembered frame contains the PREVIOUS song's actual image and all its
        // background colours. It remains intact during fade-out even if Media3 has already
        // switched its metadata to the NEXT song.
        val visualKey = "${mediaMetadata.id}|${mediaMetadata.thumbnailUrl}|${artworkAspect}"
        var shownGradient by remember { mutableStateOf<ImmersiveGradientFrame?>(null) }
        val gradientAlpha = remember { Animatable(0f) }
        // Selection changes must START the outgoing fade immediately. Crucially, its
        // coroutine is keyed only by whether the OLD frame is still on screen, not by
        // each incoming song id. Rapid A -> B -> C -> D skips therefore cannot restart
        // the same A -> grey animation four times and keep A visible indefinitely.
        val fadingOldFrame = shouldFadeOutImmersiveFrame(
            shownGradient?.key,
            visualKey,
            visible,
        )
        LaunchedEffect(fadingOldFrame) {
            if (fadingOldFrame) {
                gradientAlpha.animateTo(
                    0f,
                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
                )
                shownGradient = null
            }
        }
        // Only the newest selected song can enter AFTER the old frame has fully
        // disappeared and its own final bitmap and background are BOTH ready.
        LaunchedEffect(
            visualKey,
            coverAndGradientReady,
            edge,
            floor,
            artworkTone.bottomTexture,
            artworkTone.displayUrl,
            visible,
            fadingOldFrame,
        ) {
            if (!visible || !coverAndGradientReady || fadingOldFrame) return@LaunchedEffect
            val readyUrl = artworkTone.displayUrl ?: return@LaunchedEffect
            if (shownGradient == null) {
                shownGradient = ImmersiveGradientFrame(
                    key = visualKey,
                    edge = edge,
                    floor = floor,
                    imageUrl = readyUrl,
                    landscape = artworkTone.landscape,
                    bottomTexture = artworkTone.bottomTexture,
                )
            }
            // Also handles reopening the sheet while its previous fade-out was being
            // cancelled. Alpha carries on from its current value, not a new zero.
            if (shownGradient?.key == visualKey) {
                gradientAlpha.animateTo(
                    1f,
                    animationSpec = tween(durationMillis = 630, easing = FastOutSlowInEasing),
                )
            }
        }
        /*
         * Where the cover ends, as a fraction of the sheet. The page has to be exactly edge at
         * that line and nowhere else, so the stop is computed rather than guessed.
         */
        val seam = (artworkHeight / maxHeight).coerceIn(0.05f, 0.95f)
        val settled = (seam + 0.44f).coerceAtMost(1f)

        val frame = shownGradient
        val pageBackground = Brush.verticalGradient(
            0f to (frame?.edge ?: IMMERSIVE_NEUTRAL_COLOR),
            seam to (frame?.edge ?: IMMERSIVE_NEUTRAL_COLOR),
            settled to (frame?.floor ?: IMMERSIVE_NEUTRAL_COLOR),
            1f to (frame?.floor ?: IMMERSIVE_NEUTRAL_COLOR),
        )

        // Preload the NEXT image invisibly. Coil must finish decoding this exact URL
        // before the frame is allowed to replace the old one. It is never rendered early.
        if (artworkTone.ready && artworkTone.displayUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artworkTone.displayUrl)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { coverImageReady = true },
                onError = { coverImageReady = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(artworkHeight)
                    .graphicsLayer(alpha = 0f),
            )
        }

        // One layer, one alpha: the old cover+scrim+page leave together, then the
        // new cover+scrim+page enter together. There is NO separately animated colour
        // layer that can lag behind the image.
        Box(modifier = Modifier.fillMaxSize().background(IMMERSIVE_NEUTRAL_COLOR))
        if (frame != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(alpha = if (presentingVideo) 0f else gradientAlpha.value),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(pageBackground))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(artworkHeight)
                        .clipToBounds()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !presentingVideo,
                            onClick = onShowLyrics,
                        ),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(frame.imageUrl)
                            .crossfade(false)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = if (frame.landscape) 1.06f else 1f,
                                scaleY = if (frame.landscape) 1.06f else 1f,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                run {
                                    val fade =
                                        immersiveFadeProfile(
                                            bottomTexture = frame.bottomTexture,
                                            landscape = frame.landscape,
                                        )
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        fade.start to Color.Transparent,
                                        fade.firstStop to
                                            frame.edge.copy(alpha = fade.firstAlpha),
                                        fade.secondStop to
                                            frame.edge.copy(alpha = fade.secondAlpha),
                                        fade.thirdStop to
                                            frame.edge.copy(alpha = fade.thirdAlpha),
                                        1f to frame.edge,
                                    )
                                },
                            ),
                    )
                }
            }
        }

        // VIDEO still switches instantly on the first rendered frame, independently of
        // the slow cover-to-neutral-to-cover animation. The request guard stays intact.
        if (presentingVideo) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(IMMERSIVE_NEUTRAL_COLOR, Color(0xFF1E1E1E)),
                    ),
                ),
            )
        }
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = videoFrameTop, start = videoSidePadding, end = videoSidePadding)
                    .fillMaxWidth()
                    .height(videoFrameHeight)
                    .graphicsLayer(alpha = if (presentingVideo) 1f else 0f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            player = playerConnection.player
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            keepScreenOn = true
                        }
                    },
                    update = { playerView ->
                        if (playerView.player !== playerConnection.player) {
                            playerView.player = playerConnection.player
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        /*
         * Immersive editing deliberately starts where the artwork ends and stops before the queue
         * rail. The cover/dissolve is the background of this design, not a draggable card.
         *
         * Reuse the exact Light v2 solver for the four familiar blocks. This is one positioning
         * engine across Capsule rather than a second approximation of magnets/reflow.
         */
        val editorHeight =
            immersiveEditorHeight(
                totalHeight = maxHeight,
                artworkHeight = artworkHeight,
                bottomPadding = bottomPadding,
            )

        LaunchedEffect(immersiveEditEnabled) {
            if (!immersiveEditEnabled) {
                immersiveNestedEditActive = false
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = artworkHeight)
                    .height(editorHeight),
        ) {
            CapsuleLightCanvasV2(
                order = immersiveOrder,
                positionsDp = immersivePositions,
                editable = immersiveEditEnabled,
                viewportHeight = editorHeight,
                onLayoutSettled = { positions, reordered ->
                    val safeOrder =
                        decodeCapsuleImmersiveOrder(
                            encodeCapsuleImmersiveOrder(reordered),
                        )
                    val safePositions =
                        positions
                            .filterKeys { it in CapsuleImmersiveBaseOrder }
                            .mapValues { (_, value) -> value.coerceAtLeast(0f) }

                    immersiveOrder = safeOrder
                    immersivePositions = safePositions
                    onImmersiveOrderEncodedChange(
                        encodeCapsuleImmersiveOrder(safeOrder),
                    )
                    onImmersivePositionsEncodedChange(
                        encodeCapsuleLightCanvasPositions(safePositions),
                    )
                    immersiveNestedEditActive = false
                },
                onEditStarted = {},
                externalGestureActive = immersiveNestedEditActive,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(editorHeight),
            ) { block, _ ->
                val beginNestedEdit: () -> Unit = {
                    immersiveNestedEditActive = true
                }

                when (block) {
                    CapsuleLightBlock.METADATA -> {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                        ) {
                            CapsuleLightReorderRow(
                                order = immersiveMetadataOrder,
                                editable = immersiveEditEnabled,
                                weightFor = { item ->
                                    when (item) {
                                        CapsuleLightMetadataItem.TEXT -> 5.8f
                                        CapsuleLightMetadataItem.FAVORITE -> 1f
                                    }
                                },
                                onOrderChange = { reordered ->
                                    immersiveMetadataOrder =
                                        decodeCapsuleLightMetadataOrder(
                                            encodeCapsuleLightMetadataOrder(reordered),
                                        )
                                },
                                onOrderSettled = { reordered ->
                                    val safe =
                                        decodeCapsuleLightMetadataOrder(
                                            encodeCapsuleLightMetadataOrder(reordered),
                                        )
                                    immersiveMetadataOrder = safe
                                    onImmersiveMetadataOrderEncodedChange(
                                        encodeCapsuleLightMetadataOrder(safe),
                                    )
                                    immersiveNestedEditActive = false
                                },
                                onEditStarted = beginNestedEdit,
                                modifier = Modifier.fillMaxWidth(),
                            ) { item ->
                                when (item) {
                                    CapsuleLightMetadataItem.TEXT -> {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = mediaMetadata.title,
                                                color = textColor,
                                                fontSize = 28.sp,
                                                lineHeight = 33.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (mediaMetadata.explicit) {
                                                    ExplicitBadge(
                                                        color = textColor.copy(alpha = 0.62f),
                                                    )
                                                    Spacer(Modifier.width(5.dp))
                                                }
                                                Text(
                                                    text =
                                                        mediaMetadata.artists
                                                            .joinToString { it.name },
                                                    color = textColor.copy(alpha = 0.62f),
                                                    fontSize = 17.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier =
                                                        Modifier.clickable(
                                                            interactionSource =
                                                                remember {
                                                                    MutableInteractionSource()
                                                                },
                                                            indication = null,
                                                            enabled =
                                                                navigableArtists.isNotEmpty() &&
                                                                    !immersiveEditEnabled,
                                                        ) {
                                                            when (navigableArtists.size) {
                                                                1 ->
                                                                    onArtistSelected(
                                                                        navigableArtists.first(),
                                                                    )
                                                                else -> showArtistPicker = true
                                                            }
                                                        },
                                                )
                                            }
                                        }
                                    }

                                    CapsuleLightMetadataItem.FAVORITE -> {
                                        val favoriteInteraction =
                                            remember { MutableInteractionSource() }
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(52.dp)
                                                    .clip(CircleShape)
                                                    .clickable(
                                                        interactionSource = favoriteInteraction,
                                                        indication = null,
                                                        enabled = !immersiveEditEnabled,
                                                        onClick = onToggleLike,
                                                    ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CapsuleFavoriteIcon(
                                                liked = liked,
                                                interactionSource = favoriteInteraction,
                                                tint =
                                                    if (liked) {
                                                        CapsuleFavoriteColors.selected(textColor)
                                                    } else {
                                                        textColor
                                                    },
                                                modifier = Modifier.size(28.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            // CanvasV2 contributes its own fixed 8dp dock gap.
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    CapsuleLightBlock.PROGRESS -> {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                        ) {
                            CapsuleThinSlider(
                                value = shownPosition.toFloat(),
                                valueRange =
                                    0f..safeDuration
                                        .coerceAtLeast(1L)
                                        .toFloat(),
                                enabled =
                                    safeDuration > 0L &&
                                        !immersiveEditEnabled,
                                activeColor = textColor,
                                inactiveColor = textColor.copy(alpha = 0.22f),
                                onValueChange = { onSeekPreview(it.toLong()) },
                                onValueChangeFinished = onSeekFinished,
                                modifier = Modifier.fillMaxWidth().height(22.dp),
                                trackHeight = 8.dp,
                                thumbRadius = 5.dp,
                            )

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    makeTimeString(shownPosition),
                                    color = textColor.copy(alpha = 0.55f),
                                    fontSize = 13.sp,
                                )
                                Text(
                                    makeTimeString(safeDuration),
                                    color = textColor.copy(alpha = 0.55f),
                                    fontSize = 13.sp,
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    CapsuleLightBlock.MODE_SWITCH -> {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                        ) {
                            CapsuleLightReorderRow(
                                order = immersiveModeOrder,
                                editable = immersiveEditEnabled,
                                weightFor = { item ->
                                    when (item) {
                                        CapsuleLightModeItem.SHUFFLE -> 1f
                                        CapsuleLightModeItem.AUDIO_VIDEO -> 4.5f
                                        CapsuleLightModeItem.SLEEP -> 1f
                                    }
                                },
                                onOrderChange = { reordered ->
                                    immersiveModeOrder =
                                        decodeCapsuleLightModeOrder(
                                            encodeCapsuleLightModeOrder(reordered),
                                        )
                                },
                                onOrderSettled = { reordered ->
                                    val safe =
                                        decodeCapsuleLightModeOrder(
                                            encodeCapsuleLightModeOrder(reordered),
                                        )
                                    immersiveModeOrder = safe
                                    onImmersiveModeOrderEncodedChange(
                                        encodeCapsuleLightModeOrder(safe),
                                    )
                                    immersiveNestedEditActive = false
                                },
                                onEditStarted = beginNestedEdit,
                                modifier = Modifier.fillMaxWidth(),
                            ) { item ->
                                when (item) {
                                    CapsuleLightModeItem.SHUFFLE -> {
                                        IconButton(
                                            onClick = {
                                                playerConnection.player.shuffleModeEnabled =
                                                    !shuffleEnabled
                                            },
                                            enabled = !immersiveEditEnabled,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.shuffle),
                                                stringResource(R.string.shuffle),
                                                tint =
                                                    textColor.copy(
                                                        alpha =
                                                            if (shuffleEnabled) {
                                                                1f
                                                            } else {
                                                                0.5f
                                                            },
                                                    ),
                                                modifier = Modifier.size(21.dp),
                                            )
                                        }
                                    }

                                    CapsuleLightModeItem.AUDIO_VIDEO -> {
                                        val edgeInsets =
                                            capsuleLightAvOuterInsets(immersiveModeOrder)
                                        CapsuleAudioVideoToggle(
                                            lightStyle = true,
                                            state = videoPlaybackState,
                                            textColor = textColor,
                                            enabled = !immersiveEditEnabled,
                                            onAudioClick = {
                                                playerConnection.service.setCapsulePlaybackMode(
                                                    CapsulePlaybackMode.AUDIO,
                                                )
                                            },
                                            onVideoClick = {
                                                playerConnection.service.setCapsulePlaybackMode(
                                                    CapsulePlaybackMode.VIDEO,
                                                )
                                            },
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(
                                                        start = edgeInsets.start,
                                                        end = edgeInsets.end,
                                                    ),
                                            lightOrder = immersiveAvOrder,
                                            lightEditable = immersiveEditEnabled,
                                            onLightOrderChange = { reordered ->
                                                immersiveAvOrder =
                                                    decodeCapsuleLightAvOrder(
                                                        encodeCapsuleLightAvOrder(reordered),
                                                    )
                                            },
                                            onLightOrderSettled = { reordered ->
                                                val safe =
                                                    decodeCapsuleLightAvOrder(
                                                        encodeCapsuleLightAvOrder(reordered),
                                                    )
                                                immersiveAvOrder = safe
                                                onImmersiveAvOrderEncodedChange(
                                                    encodeCapsuleLightAvOrder(safe),
                                                )
                                                immersiveNestedEditActive = false
                                            },
                                            onLightEditStarted = beginNestedEdit,
                                        )
                                    }

                                    CapsuleLightModeItem.SLEEP -> {
                                        IconButton(
                                            onClick = { showSleepTimerDialog = true },
                                            enabled = !immersiveEditEnabled,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.bedtime),
                                                stringResource(R.string.sleep_timer),
                                                tint =
                                                    textColor.copy(
                                                        alpha =
                                                            if (sleepTimerEnabled) {
                                                                1f
                                                            } else {
                                                                0.5f
                                                            },
                                                    ),
                                                modifier = Modifier.size(21.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(2.dp))
                        }
                    }

                    CapsuleLightBlock.CONTROLS -> {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                        ) {
                            CapsuleLightControls(
                                textColor = textColor,
                                shuffleEnabled = shuffleEnabled,
                                repeatMode = repeatMode,
                                enabled = true,
                                canSkipPrevious = canSkipPrevious,
                                canSkipNext = canSkipNext,
                                onShuffle = {
                                    playerConnection.player.shuffleModeEnabled =
                                        !shuffleEnabled
                                },
                                onPrevious = {
                                    playerConnection.player.seekToPrevious()
                                },
                                onNext = {
                                    playerConnection.player.seekToNext()
                                },
                                onRepeat = {
                                    playerConnection.player.toggleRepeatMode()
                                },
                                orbit = {
                                    CapsuleOrbitButton(
                                        isPlaying = isPlaying,
                                        isLoading = isLoading,
                                        visible = visible,
                                        color = textColor,
                                        onClick = {
                                            playerConnection.player.togglePlayPause()
                                        },
                                    )
                                },
                                onMenuClick = onMenuClick,
                                interactionEnabled = !immersiveEditEnabled,
                                order = immersiveTransportOrder,
                                editable = immersiveEditEnabled,
                                onOrderChange = { reordered ->
                                    immersiveTransportOrder =
                                        decodeCapsuleLightTransportOrder(
                                            encodeCapsuleLightTransportOrder(reordered),
                                        )
                                },
                                onOrderSettled = { reordered ->
                                    val safe =
                                        decodeCapsuleLightTransportOrder(
                                            encodeCapsuleLightTransportOrder(reordered),
                                        )
                                    immersiveTransportOrder = safe
                                    onImmersiveTransportOrderEncodedChange(
                                        encodeCapsuleLightTransportOrder(safe),
                                    )
                                    immersiveNestedEditActive = false
                                },
                                onEditStarted = beginNestedEdit,
                            )
                        }
                    }

                    CapsuleLightBlock.ARTWORK,
                    CapsuleLightBlock.LYRIC,
                    -> Unit
                }
            }
        }

        /*
         * The rail at the foot of the screen. Cosmo shows the same mark with the queue behind it,
         * so nothing new has to be learned to find it here.
         */
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = bottomPadding + ImmersiveQueueRailLift)
                    .height(ImmersiveQueueRailTouchHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !immersiveEditEnabled,
                        onClick = onExpandQueue,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(width = ImmersiveQueueRailWidth, height = ImmersiveQueueRailHeight)
                        .clip(CircleShape)
                        .background(textColor.copy(alpha = 0.32f)),
            )
        }
    }
}

/** The Activity window behind a Composable, or null when there is not one to reach. */
private tailrec fun Context.immersiveWindow(): Window? =
    when (this) {
        is Activity -> window
        is ContextWrapper -> baseContext.immersiveWindow()
        else -> null
    }

