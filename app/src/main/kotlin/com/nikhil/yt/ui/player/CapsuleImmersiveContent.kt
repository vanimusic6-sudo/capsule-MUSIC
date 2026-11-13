/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
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
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalImmersiveStatusBarRequest
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
    val nearWhiteBackground: Boolean,
    val edgeBands: List<Color>,
    val colorField: CapsuleColorField?,
    val visibleTail: CapsuleColorField?,
)

/**
 * Immersion: a seamless dissolve from the original photo into the MUTED,
 * EXTRACTED album colour. Never target black/graphite. That was the cause of
 * the unwanted white->grey->black ramp in user screenshots.
 *
 * The source edge and sampled floor belong to ONE decoded frame, and both
 * shaders use the same physical screen Y. Keep white controls constant.
 */
internal val ImmersiveControlInk = Color.White

/** VIDEO never inherits album-derived wallpaper when its own ambient mode is off. */
internal fun immersivePageBaseColor(presentingVideo: Boolean, hasArtwork: Boolean, floor: Color): Color =
    if (presentingVideo) Color(0xFF111116)
    else if (hasArtwork) floor else IMMERSIVE_NEUTRAL_COLOR

/** The source colour is the actual sampled lower crop. Never recolour its seam. */
internal fun immersiveSeamColor(edge: Color, floor: Color): Color = edge

private fun immersiveSmooth5(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

/**
 * Blend from the real album edge into the same image's subtly muted tone.
 * The transition begins at 78% of artwork height, settles shortly below it,
 * and stays at that exact extracted colour for the remaining player page.
 * No fabricated blue, fixed graphite or forced dark-ink destination.
 *
 * Quintic easing gives zero first/second derivatives at both endpoints.
 * tailFraction is a fraction of the physical viewport, not artwork pixels.
 */
internal fun immersiveContinuousPageColor(
    edge: Color,
    muted: Color,
    pageFraction: Float,
    coverFraction: Float,
    tailFraction: Float,
): Color {
    val cover = coverFraction.coerceIn(0.05f, 0.95f)
    // Preserve the album through its upper 78%, then blend briefly
    // beyond the artwork boundary into the sampled muted backdrop.
    val start = cover * 0.77f
    val end = (cover + tailFraction.coerceAtLeast(0.02f)).coerceAtMost(0.86f)
    val shade = immersiveSmooth5(start, end, pageFraction.coerceIn(0f, 1f))
    return Color(
        red = edge.red + (muted.red - edge.red) * shade,
        green = edge.green + (muted.green - edge.green) * shade,
        blue = edge.blue + (muted.blue - edge.blue) * shade,
        alpha = 1f,
    )
}

/** 49 immutable GPU stops across the entire viewport; one cached brush. */
internal fun immersiveContinuousPageStops(
    edge: Color,
    muted: Color,
    coverFraction: Float,
    tailFraction: Float,
): Array<Pair<Float, Color>> =
    Array(49) { index ->
        val position = index / 48f
        position to immersiveContinuousPageColor(edge, muted, position, coverFraction, tailFraction)
    }

/**
 * Separate WHAT the photo reveals (page RGB) from HOW it reveals it (alpha).
 * Upper two-thirds of the cover remain truly unaltered. Busy art receives
 * a small extra lead so dense credits don't form a dark line at the seam.
 */
internal data class ImmersiveFadeProfile(val start: Float)

internal fun immersiveFadeProfile(
    bottomTexture: Float,
    landscape: Boolean,
): ImmersiveFadeProfile {
    val texture = immersiveSmooth5(0.40f, 1f, bottomTexture.coerceIn(0f, 1f))
    return ImmersiveFadeProfile(
        start = 0.67f - texture * 0.055f - if (landscape) 0.012f else 0f,
    )
}

/** One quintic image dissolve; no independently darkening photo shader. */
internal fun immersiveCoverFadeAlpha(profile: ImmersiveFadeProfile, fraction: Float): Float =
    immersiveSmooth5(profile.start, 1f, fraction.coerceIn(0f, 1f))

/**
 * The photo's shader samples the page RGB at the IDENTICAL screen Y for each
 * cover stop. Its last stop is opaque and equals the page exactly, regardless
 * of album height, source brightness, or texture.
 */
internal fun immersiveContinuousCoverStops(
    edge: Color,
    muted: Color,
    coverFraction: Float,
    tailFraction: Float,
    profile: ImmersiveFadeProfile,
): Array<Pair<Float, Color>> =
    Array(49) { index ->
        val photoFraction = index / 48f
        val pageFraction = photoFraction * coverFraction
        val tone = immersiveContinuousPageColor(
            edge, muted, pageFraction, coverFraction, tailFraction,
        )
        photoFraction to tone.copy(alpha = immersiveCoverFadeAlpha(profile, photoFraction))
    }

/**
 * Identical original (pre-October-gradient-experiments) Immersion curves.
 * Used for EVERY normal/coloured album. Only unusually pale artwork uses
 * the guarded, slightly longer continuation path.
 */
internal data class OriginalImmersiveFadeProfile(
    val start: Float,
    val firstStop: Float,
    val firstAlpha: Float,
    val secondStop: Float,
    val secondAlpha: Float,
    val thirdStop: Float,
    val thirdAlpha: Float,
)

internal fun immersiveOriginalFadeProfile(
    bottomTexture: Float,
    landscape: Boolean,
): OriginalImmersiveFadeProfile {
    val texture = bottomTexture.coerceIn(0f, 1f)

    // Keep the current calm profile EXACTLY as-is. Only genuinely difficult lower crops leave it.
    // Above the threshold, smoothly approach the hard endpoint that worked well in a9ef7ff.
    val rawBoost = ((texture - 0.45f) / 0.55f).coerceIn(0f, 1f)
    val boost = rawBoost * rawBoost * (3f - 2f * rawBoost) // smoothstep: no visible kink at 0.45

    return if (landscape) {
        val calmStart = 0.32f
        val calmFirstStop = 0.52f
        val calmFirstAlpha = 0.15f
        val calmSecondStop = 0.70f
        val calmSecondAlpha = 0.46f
        val calmThirdStop = 0.87f
        val calmThirdAlpha = 0.80f

        // Exact texture=1 endpoint from a9ef7ff.
        val hardStart = 0.12f
        val hardFirstStop = 0.37f
        val hardFirstAlpha = 0.44f
        val hardSecondStop = 0.57f
        val hardSecondAlpha = 0.85f
        val hardThirdStop = 0.79f
        val hardThirdAlpha = 1f

        OriginalImmersiveFadeProfile(
            start = calmStart + (hardStart - calmStart) * boost,
            firstStop = calmFirstStop + (hardFirstStop - calmFirstStop) * boost,
            firstAlpha = calmFirstAlpha + (hardFirstAlpha - calmFirstAlpha) * boost,
            secondStop = calmSecondStop + (hardSecondStop - calmSecondStop) * boost,
            secondAlpha = calmSecondAlpha + (hardSecondAlpha - calmSecondAlpha) * boost,
            thirdStop = calmThirdStop + (hardThirdStop - calmThirdStop) * boost,
            thirdAlpha = calmThirdAlpha + (hardThirdAlpha - calmThirdAlpha) * boost,
        )
    } else {
        val calmStart = 0.42f
        val calmFirstStop = 0.60f
        val calmFirstAlpha = 0.15f
        val calmSecondStop = 0.74f
        val calmSecondAlpha = 0.46f
        val calmThirdStop = 0.89f
        val calmThirdAlpha = 0.80f

        // Exact texture=1 endpoint from a9ef7ff.
        val hardStart = 0.11f
        val hardFirstStop = 0.39f
        val hardFirstAlpha = 0.50f
        val hardSecondStop = 0.56f
        val hardSecondAlpha = 0.90f
        val hardThirdStop = 0.79f
        val hardThirdAlpha = 1f

        OriginalImmersiveFadeProfile(
            start = calmStart + (hardStart - calmStart) * boost,
            firstStop = calmFirstStop + (hardFirstStop - calmFirstStop) * boost,
            firstAlpha = calmFirstAlpha + (hardFirstAlpha - calmFirstAlpha) * boost,
            secondStop = calmSecondStop + (hardSecondStop - calmSecondStop) * boost,
            secondAlpha = calmSecondAlpha + (hardSecondAlpha - calmSecondAlpha) * boost,
            thirdStop = calmThirdStop + (hardThirdStop - calmThirdStop) * boost,
            thirdAlpha = calmThirdAlpha + (hardThirdAlpha - calmThirdAlpha) * boost,
        )
    }
}

/**
 * Display-only shaping for the cover -> player dissolve.
 *
 * The sampled artwork colour is left untouched. The seam is only nudged toward the already
 * existing darker floor so the lower fade has more depth, while the upper haze is barely lighter
 * to keep the transition smoky instead of reading as a hard dark band.
 */
internal fun immersiveOriginalSeamColor(edge: Color, floor: Color): Color =
    lerp(edge, floor, 0.45f)

internal fun immersiveUpperHazeColor(edge: Color): Color =
    lerp(edge, Color.White, 0.055f)


/** How much of the sheet the cover takes before it starts to go. */
internal const val ImmersiveStatusBarHideProgress = 0.62f

internal fun immersiveStatusBarShouldHide(
    open: Boolean,
    expansionProgress: Float,
): Boolean =
    open &&
        expansionProgress.coerceIn(0f, 1f) >= ImmersiveStatusBarHideProgress

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
internal fun CapsuleImmersiveContent(
    mediaMetadata: MediaMetadata,
    artworkTone: ImmersiveArtworkTone,
    videoAmbientBands: List<Color> = emptyList(),
    videoAmbientField: CapsuleColorField? = null,
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
    controlsActive: Boolean = true,
    transitionForegroundAlpha: () -> Float = { 1f },
    /**
     * Raw bottom-sheet travel from collapsed (0) to expanded (1).
     *
     * System-bar geometry is now fixed independently of visibility, so Immersive no longer has
     * to wait until the sheet physically touches the top. The status bar starts leaving during
     * the final third of the player's travel and is already in motion before the sheet arrives.
     */
    expansionProgress: Float = if (open) 1f else 0f,
) {
    val onScreen = appIsOnScreen()
    if (!onScreen || (!open && expansionProgress <= 0.001f)) {
        Box(Modifier.fillMaxSize())
        return
    }
    val visible = open

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val videoPlaybackState by playerConnection.service.videoPlaybackState.collectAsState()

    val isLoading = playbackState == Player.STATE_BUFFERING

    val isVideo = videoPlaybackState.presentingVideo
    val needsVideoSurface = videoPlaybackState.needsSurface

    /*
     * Request fullscreen from the Activity instead of manipulating the Window here.
     *
     * This is deliberately progress-driven: at 62% of the trip the bar begins to leave while the
     * player still has distance left, rather than disappearing only after the sheet hits the top.
     * The request is foreground-gated so returning directly into an expanded Immersive player
     * re-issues the hide request, and leaving the player always re-issues the matching show.
     */
    val requestStatusBarHidden = LocalImmersiveStatusBarRequest.current
    val shouldHideSystemBars =
        onScreen &&
            immersiveStatusBarShouldHide(
                open = open,
                expansionProgress = expansionProgress,
            )
    DisposableEffect(requestStatusBarHidden, shouldHideSystemBars) {
        if (shouldHideSystemBars) {
            requestStatusBarHidden(true)
        }

        onDispose {
            if (shouldHideSystemBars) {
                requestStatusBarHidden(false)
            }
        }
    }

    /*
     * One artist opens their page; several ask which was meant. Same rule and the same dialog as
     * every other screen, because a track credited to two people should not behave differently
     * depending on which player design is switched on.
     */
    val artistCredits = rememberArtistCredits(mediaMetadata.artists)
    val navigableArtists = rememberNavigableArtists(artistCredits)
    // Match Light/Cosmo: close the chooser rather than retarget it on song change.
    var showArtistPicker by remember(mediaMetadata.id) { mutableStateOf(false) }

    if (showArtistPicker) {
        CapsuleArtistPickerDialog(
            artists = artistCredits,
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
            active = sleepTimerEnabled,
            onMinutesChange = { sleepTimerValue = it },
            onEndOfSong = {
                playerConnection.service.sleepTimer.start(-1)
                showSleepTimerDialog = false
            },
            onClear = {
                playerConnection.service.sleepTimer.clear()
                showSleepTimerDialog = false
            },
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
        // The host samples this exact visible crop once for the player and Lyrics.
        val artworkAspect = maxWidth.value / artworkHeight.value.coerceAtLeast(1f)
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
        // Keep the successful preloader's REAL decoded painter available until
        // the visible Coil node completes. Previously we discarded the only
        // proven image, then waited up to six seconds on another painter while
        // displaying a grey artwork slot.
        var decodedCoverPainter by remember(mediaMetadata.id) {
            mutableStateOf<Painter?>(null)
        }
        // A transient Coil error previously left the invisible preload mounted
        // forever with no new request until the entire player was reconstructed.
        // One retry is permitted per track/URL; a fresh track resets this budget.
        var coverLoadRetry by remember(mediaMetadata.id, artworkTone.displayUrl) {
            mutableIntStateOf(0)
        }
        val coverAndGradientReady = canRevealImmersiveArtwork(artworkTone, coverImageReady)
        // The remembered frame contains the PREVIOUS song's actual image and all its
        // background colours. It remains intact during fade-out even if Media3 has already
        // switched its metadata to the NEXT song.
        val visualKey = "${mediaMetadata.id}|${artworkTone.displayUrl}|${artworkAspect}"
        // A decoded bitmap that has been displayed through VIDEO must remain
        // opaque on VIDEO -> AUDIO. The track-switch gradient fade is independent
        // of the cover/video morph and can be interrupted by sheet visibility.
        // Never multiply the two independently animated opacities: that was
        // revealing the video underneath as a broken/translucent album photo.
        var keepVideoReturnArtworkOpaque by remember(visualKey) { mutableStateOf(false) }
        LaunchedEffect(visualKey, isVideo, needsVideoSurface) {
            if (isVideo || needsVideoSurface) keepVideoReturnArtworkOpaque = true
        }
        var shownGradient by remember { mutableStateOf<ImmersiveGradientFrame?>(null) }
        // The last actual photo and page live underneath the next prepared
        // pair. Never fade to neutral, then mount a new bitmap 630ms later.
        var outgoingFrame by remember { mutableStateOf<ImmersiveGradientFrame?>(null) }
        val gradientAlpha = remember { Animatable(1f) }
        LaunchedEffect(
            visualKey, coverAndGradientReady, edge, floor,
            artworkTone.bottomTexture, artworkTone.displayUrl, visible, isVideo,
        ) {
            if (!visible || !coverAndGradientReady ||
                shownGradient?.key == visualKey
            ) return@LaunchedEffect
            val readyUrl = artworkTone.displayUrl ?: return@LaunchedEffect
            val old = shownGradient
            outgoingFrame = old
            shownGradient = ImmersiveGradientFrame(
                key = visualKey,
                edge = edge,
                floor = floor,
                imageUrl = readyUrl,
                landscape = artworkTone.landscape,
                bottomTexture = artworkTone.bottomTexture,
                nearWhiteBackground = artworkTone.nearWhiteBackground,
                edgeBands = artworkTone.edgeBands,
                colorField = artworkTone.colorField,
                visibleTail = artworkTone.visibleTail,
            )
            // New background + photo appear together only after their first
            // render has completed, instead of sequential fade-out/in.
            gradientAlpha.snapTo(if (old == null || isVideo) 1f else 0f)
        }
        // Keep the original background interpolation for normal artwork.
        // Only near-white, low-saturation covers use the slightly more
        // diffuse current technique to avoid a harsh white/grey seam.
        val frame = shownGradient
        // The offscreen preloader may have decoded this cover successfully,
        // yet the visible AsyncImage can still lose its own Coil painter
        // during a GPU/surface lifecycle change. Rebind only that painter
        // once if its own load hangs or fails, never the whole gradient.
        var visibleArtworkRebind by remember(frame?.key, frame?.imageUrl) {
            mutableIntStateOf(0)
        }
        var visibleArtworkPending by remember(frame?.key, frame?.imageUrl) {
            mutableStateOf(false)
        }
        var visibleArtworkDecoded by remember(frame?.key, frame?.imageUrl) {
            mutableStateOf(false)
        }
        LaunchedEffect(frame?.key, frame?.imageUrl, visibleArtworkRebind, visibleArtworkPending) {
            frame?.takeIf {
                it.key == visualKey && it.imageUrl.isNotBlank()
            } ?: return@LaunchedEffect
            if (!visibleArtworkPending || visibleArtworkRebind > 0) return@LaunchedEffect
            // The bitmap is already decoded; a stalled second painter must
            // not hold a blank slot for another six seconds.
            delay(if (decodedCoverPainter != null) 1_000L else 3_000L)
            if (visibleArtworkPending && visibleArtworkRebind == 0) {
                Timber.tag("CapsuleArtwork").w(
                    "immersive-artwork-stalled id=%s; rebind visible painter", mediaMetadata.id,
                )
                visibleArtworkRebind++
            }
        }
        val nearWhite = frame?.nearWhiteBackground == true
        val pageEdge = frame?.edge ?: IMMERSIVE_NEUTRAL_COLOR
        val pageFloor = frame?.floor ?: IMMERSIVE_NEUTRAL_COLOR
        // The backdrop and video-window filler use the exact same unaltered
        // lower edge. Mixing 45% dark floor INTO the seam created a 1px band.
        val seamColor = immersiveSeamColor(pageEdge, pageFloor)
        val density = LocalDensity.current
        val pageHeightPx = with(density) { maxHeight.toPx() }
        val coverHeightPx = with(density) { artworkHeight.toPx() }
        val coverFraction = (coverHeightPx / pageHeightPx.coerceAtLeast(1f))
            .coerceIn(0.05f, 0.95f)
        // Exception ONLY for almost-white art: 72dp -> 80dp, with a
        // one-percentage-point earlier soft curve. Other covers are untouched.
        val extensionPx = with(density) { 80.dp.toPx() }
            .coerceAtMost((pageHeightPx - coverHeightPx).coerceAtLeast(1f) * 0.38f)
        val tailFraction = (extensionPx / pageHeightPx.coerceAtLeast(1f))
            .coerceIn(0.02f, 0.08f)
        val artworkBands = frame?.edgeBands?.takeIf { it.isNotEmpty() }
            ?: remember(seamColor) { listOf(seamColor) }
        // Live video light is painted only on the FLOOR; album-photo dissolve
        // retains the exact colours of its own pixels while video morphs in.
        val pageBands = if (isVideo && videoAmbientBands.isNotEmpty())
            videoAmbientBands else artworkBands
        // The current image-derived field supplies video ambience; static
        // audio uses only its lower edge to match the picture.
        val pageField = if (isVideo && videoAmbientField != null)
            videoAmbientField else frame?.colorField

        // One render result, one commit, one visual key. The previous design
        // had TWO independent render coroutines and a readiness callback:
        // one often won by a frame and the other faded in as a visible wave.
        val paintKey = remember(frame?.key, coverFraction, tailFraction) {
            "${frame?.key}|$coverFraction|$tailFraction"
        }
        // One prepared page texture for one decoded cover. The previous
        // pair also built a separate tinted PHOTO overlay, which repeatedly
        // caused horizontal lines and made the real image look washed out.
        var lastReadyPaint by remember { mutableStateOf<ImageBitmap?>(null) }
        val outgoingPaint = remember(paintKey) { lastReadyPaint }
        var matchedPaint by remember(paintKey) {
            mutableStateOf<ImageBitmap?>(null)
        }
        LaunchedEffect(
            paintKey, frame?.colorField, frame?.visibleTail, frame?.edgeBands, pageFloor,
            nearWhite, frame?.bottomTexture, frame?.landscape,
        ) {
            val readyFrame = frame ?: return@LaunchedEffect
            val colours = readyFrame.edgeBands.ifEmpty { listOf(seamColor) }
            matchedPaint = withContext(Dispatchers.Default) {
                immersiveSpatialFieldBitmap(
                    bands = colours,
                    fallbackFloor = readyFrame.floor,
                    coverFraction = coverFraction,
                    tailFraction = tailFraction,
                    texture = readyFrame.bottomTexture,
                    landscape = readyFrame.landscape,
                    nearWhite = readyFrame.nearWhiteBackground,
                    photoOverlay = false,
                    field = readyFrame.colorField,
                    edgeMap = readyFrame.visibleTail,
                )
            }
            lastReadyPaint = matchedPaint
        }
        LaunchedEffect(frame?.key, matchedPaint, isVideo) {
            if (frame == null || matchedPaint == null ||
                frame.key != visualKey
            ) return@LaunchedEffect
            if (outgoingFrame == null || isVideo) {
                gradientAlpha.snapTo(1f)
                outgoingFrame = null
            } else {
                gradientAlpha.animateTo(
                    1f, tween(durationMillis = 330, easing = FastOutSlowInEasing),
                )
                outgoingFrame = null
            }
        }
        val pairReady = frame != null && matchedPaint != null
        var retainedVideoField by remember(frame?.key) {
            mutableStateOf<CapsuleColorField?>(null)
        }
        // Restored cached VIDEO colours already belong to the visible frame.
        // Do not start at zero and wait for a new Play-triggered readback.
        var videoTextureReady by remember(frame?.key) {
            mutableStateOf(isVideo && videoAmbientField != null)
        }
        val videoLayerAlpha = remember(frame?.key) {
            Animatable(if (isVideo && videoAmbientField != null) 1f else 0f)
        }
        LaunchedEffect(isVideo, videoAmbientField) {
            // A disabled VIDEO background must release its former illumination
            // immediately, even if the VIDEO view itself stays mounted.
            if (isVideo) retainedVideoField = videoAmbientField
        }
        LaunchedEffect(isVideo, videoTextureReady) {
            if (isVideo && videoTextureReady) {
                videoLayerAlpha.animateTo(1f, tween(290))
            } else if (!isVideo) {
                videoLayerAlpha.animateTo(0f, tween(220))
                retainedVideoField = null
                videoTextureReady = false
            }
        }
        val immersiveInk = ImmersiveControlInk

        // Preload the NEXT image invisibly. Coil must finish decoding this exact URL
        // before the frame is allowed to replace the old one. It is never rendered early.
        // Once this exact artwork has decoded, dispose the invisible Coil
        // preloader. Keeping it alive while VIDEO runs doubled retained image
        // nodes and graphics layers for no visual benefit.
        if (artworkTone.preview == null &&
            capsuleImmersiveNeedsArtworkPreload(coverImageReady, artworkTone.ready, artworkTone.displayUrl)
        ) {
            // Restart the Coil painter only after a failed attempt, never each
            // recomposition. The existing sampled URL and palette remain paired.
            key(mediaMetadata.id, artworkTone.displayUrl, coverLoadRetry) {
                AsyncImage(
                    model = rememberImmersiveArtworkRequest(artworkTone.displayUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { success ->
                        decodedCoverPainter = success.painter
                        coverImageReady = true
                    },
                    onError = {
                        coverImageReady = false
                        if (coverLoadRetry < 1) coverLoadRetry++
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(artworkHeight)
                        .graphicsLayer(alpha = 0f),
                )
            }
        }

        // AUDIO's colour continuation and VIDEO's backdrop are different
        // ownership domains. A VIDEO with ambient disabled must be a clean
        // neutral dark page, never the previous album's muddy colour texture.
        // On VIDEO handover the old image fades, without a permanent CPU loop.
        val audioBackdropAlpha = remember { Animatable(if (isVideo) 0f else 1f) }
        LaunchedEffect(isVideo) {
            audioBackdropAlpha.animateTo(if (isVideo) 0f else 1f, tween(280))
        }
        Box(Modifier.fillMaxSize().background(
            immersivePageBaseColor(isVideo, frame != null, pageFloor)
        )) {
            if (!isVideo && outgoingFrame != null &&
                (matchedPaint == null || gradientAlpha.value < 1f)
            ) {
                outgoingPaint?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            alpha = audioBackdropAlpha.value
                        },
                    )
                }
            }
            matchedPaint?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = gradientAlpha.value * audioBackdropAlpha.value
                    },
                )
            }
            val liveField = retainedVideoField
            if (isVideo && liveField != null && matchedPaint != null) {
                key(frame?.key) {
                    CapsuleVideoAmbientBackdrop(
                        bands = pageBands,
                        field = liveField,
                        animated = isVideo && isPlaying,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            alpha = videoLayerAlpha.value
                        },
                        fallbackFloor = pageFloor,
                        coverFraction = coverFraction,
                        tailFraction = tailFraction,
                        texture = frame?.bottomTexture ?: 0f,
                        landscape = frame?.landscape ?: false,
                        nearWhite = nearWhite,
                        onFirstTextureReady = { videoTextureReady = true },
                    )
                }
            }
        }

        CapsuleVideoArtworkTransition(
            presentingVideo = isVideo,
            needsSurface = needsVideoSurface,
            transitionKey = mediaMetadata.id,
            // Cover the final resampled pixel of the separate background layer.
            // A single dp of overlap conceals an otherwise visible sheet seam
            // without adding a blur/graphics layer or affecting the controls.
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(artworkHeight + 1.dp),
            fillArtworkSlot = true,
            // Keep the full-bleed artwork, but morph into the same rounded
            // 16:9 video window used by the other player styles.
            artworkCornerRadius = 0.dp,
            // Smaller circular finish; radius now grows continuously with
            // the container instead of jumping near the end of the morph.
            videoCornerRadius = 16.dp,
            videoSidePadding = 12.dp,
            videoWidthScale = 1.04f,
            motion = CapsuleVideoArtworkMotion.IMMERSIVE,
            blendStyle = CapsuleVideoBlendStyle.IMMERSION,
            // A static album remains joined to its page gradient while only
            // the separate VIDEO mask grows/shrinks. The previous moving
            // album/matte exposed different edge pixels at the join on close.
            fixedImmersiveArtwork = true,
            // Never place a solid matté under the photo's transparent edge:
            // the actual long diffuse background must show through.
            artworkBackground = Color.Transparent,
            artworkClickable = !isVideo,
            onArtworkClick = onShowLyrics,
            video = { active -> CapsuleVideoSurface(playerConnection.service, Modifier.fillMaxSize(), active = active) },
        ) {
            if (!pairReady) {
                Box(Modifier.fillMaxSize()) {
                    if (frame?.key == visualKey && artworkTone.preview != null) {
                        Image(
                            bitmap = artworkTone.preview,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (frame == null && !mediaMetadata.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = mediaMetadata.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0.62f to Color.Transparent,
                        1f to (if (frame != null) pageFloor else Color(0xFF151219)),
                    )))
                }
            }
            // Hold the last *real* album photo until the incoming decoded
            // cover and its matching colour continuation can overlap.
            // Existing Coil bitmap cache avoids a second remote fetch.
            val leaving = outgoingFrame
            if (!isVideo && leaving != null &&
                (matchedPaint == null || gradientAlpha.value < 1f)
            ) {
                AsyncImage(
                    model = rememberImmersiveArtworkRequest(leaving.imageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = if (leaving.landscape) 1.06f else 1f
                        scaleY = if (leaving.landscape) 1.06f else 1f
                    },
                )
            }
            if (frame != null && pairReady) {
                // Wide (16:9) video thumbnails use a 1.06x GPU overscan below.
                // Without a parent clip, those pixels escape the artwork slot,
                // continuing UNDER the page's independently sized fade and
                // forming a sharp horizontal band below the image in AUDIO mode.
                // Clip the PHOTO and its existing overlay together: do not
                // change the source crop, sampled colors or gradient stops.
                Box(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer {
                            // One retained GPU composition layer, not a new
                            // CPU blur per frame. The photo dissolves into
                            // the matched continuation underneath it.
                            compositingStrategy = CompositingStrategy.Offscreen
                            alpha = capsuleImmersiveArtworkOpacity(
                                frameMatchesTrack = frame.key == visualKey,
                                hasPresentedVideo = keepVideoReturnArtworkOpaque,
                                trackFadeAlpha = gradientAlpha.value,
                            )
                        }
                        .then(
                            if (!isVideo) Modifier.drawWithCache {
                                val fadeMask = Brush.verticalGradient(
                                    0f to Color.White,
                                    0.944f to Color.White,
                                    1f to Color.Transparent,
                                )
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(brush = fadeMask, blendMode = BlendMode.DstIn)
                                }
                            } else Modifier
                        )
                ) {
                    if (frame.key == visualKey) {
                        // The 256px rescue image is required only until
                        // the real full-resolution painter succeeds. Keeping
                        // BOTH full-screen images mounted for the entire song
                        // wasted GPU blends and memory bandwidth.
                        if (!visibleArtworkDecoded) artworkTone.preview?.let { preview ->
                            Image(
                                bitmap = preview,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().graphicsLayer(
                                    scaleX = if (frame.landscape) 1.06f else 1f,
                                    scaleY = if (frame.landscape) 1.06f else 1f,
                                ),
                            )
                        }
                    }
                    // The two requests use the same URL/size, but Coil can
                    // cancel the visible one during a player/surface transition.
                    // Show the *already decoded* image, never a neutral square,
                    // while only that visible painter retries.
                    if (visibleArtworkPending && frame.key == visualKey &&
                        frame.imageUrl == artworkTone.displayUrl
                    ) {
                        decodedCoverPainter?.let { ready ->
                            Image(
                                painter = ready,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().graphicsLayer(
                                    scaleX = if (frame.landscape) 1.06f else 1f,
                                    scaleY = if (frame.landscape) 1.06f else 1f,
                                ),
                            )
                        }
                    }
                    key(frame.key, frame.imageUrl, visibleArtworkRebind) {
                    AsyncImage(
                        model = rememberImmersiveArtworkRequest(frame.imageUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onLoading = {
                            visibleArtworkPending = true
                            visibleArtworkDecoded = false
                        },
                        onSuccess = {
                            visibleArtworkPending = false
                            visibleArtworkDecoded = true
                        },
                        onError = { error ->
                            visibleArtworkPending = false
                            visibleArtworkDecoded = false
                            Timber.tag("CapsuleArtwork").w(
                                "immersive-artwork-error id=%s retry=%s type=%s",
                                mediaMetadata.id, visibleArtworkRebind,
                                error.result.throwable::class.java.simpleName,
                            )
                            if (visibleArtworkRebind == 0) visibleArtworkRebind++
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = if (frame.landscape) 1.06f else 1f,
                                scaleY = if (frame.landscape) 1.06f else 1f,
                            ),
                    )
                    }
                    // No colour overlay on the photograph. The separate
                    // continuation begins below the artwork, exactly where
                    // the real visible crop ends.

                }
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
                    .height(editorHeight)
                    .graphicsLayer {
                        alpha = transitionForegroundAlpha().coerceIn(0f, 1f)
                    },
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
                                            CapsuleTrackTitle(
                                                mediaId = mediaMetadata.id,
                                                title = mediaMetadata.title,
                                                color = immersiveInk,
                                                enabled = !immersiveEditEnabled,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (mediaMetadata.explicit) {
                                                    ExplicitBadge(
                                                        color = immersiveInk.copy(alpha = 0.62f),
                                                    )
                                                    Spacer(Modifier.width(5.dp))
                                                }
                                                Text(
                                                    text =
                                                        artistCredits.joinToString { it.name },
                                                    color = immersiveInk.copy(alpha = 0.62f),
                                                    fontSize = CapsulePlayerArtistFontSize,
                                                    lineHeight = CapsulePlayerArtistLineHeight,
                                                    fontWeight = FontWeight.Normal,
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
                                                            when {
                                                                navigableArtists.isEmpty() -> Unit
                                                                artistCredits.size == 1 ->
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
                                        var favoriteActionToken by remember {
                                            mutableIntStateOf(0)
                                        }
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
                                                        onClick = {
                                                            favoriteActionToken += 1
                                                            onToggleLike()
                                                        },
                                                    ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CapsuleFavoriteIcon(
                                                liked = liked,
                                                interactionSource = favoriteInteraction,
                                                tint =
                                                    if (liked) {
                                                        CapsuleFavoriteColors.selected(immersiveInk)
                                                    } else {
                                                        immersiveInk
                                                    },
                                                modifier = Modifier.size(28.dp),
                                                userActionToken = favoriteActionToken,
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
                                smoothPlayback = isPlaying && sliderPosition == null,
                                activeColor = immersiveInk,
                                inactiveColor = immersiveInk.copy(alpha = 0.22f),
                                onValueChange = { onSeekPreview(it.toLong()) },
                                onValueChangeFinished = onSeekFinished,
                                modifier = Modifier.fillMaxWidth().height(22.dp),
                                trackHeight = 8.dp,
                                thumbRadius = 0.dp,
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
                                    color = immersiveInk.copy(alpha = 0.55f),
                                    fontSize = 13.sp,
                                )
                                Text(
                                    if (safeDuration > 0L) {
                                        "-${makeTimeString((safeDuration - shownPosition).coerceAtLeast(0L))}"
                                    } else {
                                        ""
                                    },
                                    color = immersiveInk.copy(alpha = 0.55f),
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
                                                    immersiveInk.copy(
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
                                            textColor = immersiveInk,
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
                                                    immersiveInk.copy(
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
                                textColor = immersiveInk,
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
                                        visible = visible && controlsActive,
                                        color = immersiveInk,
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
                    .graphicsLayer {
                        alpha = transitionForegroundAlpha().coerceIn(0f, 1f)
                    }
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
                        .background(immersiveInk.copy(alpha = 0.32f)),
            )
        }
    }
}

/** Stable Coil models prevent progress updates from rebuilding identical image requests. */
@Composable
private fun rememberImmersiveArtworkRequest(url: String?): ImageRequest {
    val context = LocalContext.current
    return remember(context, url) {
        ImageRequest.Builder(context)
            .data(url)
            // Same decode/cache target for the invisible preload and the
            // visible frame; avoids a second full-size GPU allocation.
            .size(960, 960)
            .crossfade(false)
            .build()
    }
}


/** Retire the invisible decoder after preload: no permanently transparent Image node. */
internal fun capsuleImmersiveNeedsArtworkPreload(
    imageReady: Boolean,
    toneReady: Boolean,
    url: String?,
): Boolean = !imageReady && toneReady && url != null

/**
 * Preserve an already shown album photo while VIDEO is morphing back.
 * A separate track-change dissolve must never multiply its opacity to zero.
 * Frames from a DIFFERENT song still obey the outgoing-song fade.
 */
internal fun capsuleImmersiveArtworkOpacity(
    frameMatchesTrack: Boolean,
    hasPresentedVideo: Boolean,
    trackFadeAlpha: Float,
): Float = if (frameMatchesTrack && hasPresentedVideo) 1f else trackFadeAlpha.coerceIn(0f, 1f)

