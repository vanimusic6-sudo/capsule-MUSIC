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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
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
internal data class ImmersiveGradientFrame(
    val key: String,
    val mediaId: String,
    val edge: Color,
    val floor: Color,
    val imageUrl: String,
    val landscape: Boolean,
    val bottomTexture: Float,
    val nearWhiteBackground: Boolean,
    val edgeBands: List<Color>,
    val colorField: CapsuleColorField?,
    val visibleTail: CapsuleColorField?,
    val diffusionTail: CapsuleColorField?,
    val painter: Painter,
)

internal data class ImmersivePreparedScene(
    val frame: ImmersiveGradientFrame, val paintKey: String, val bitmap: ImageBitmap,
)

/** Owned above the foreground-only composition, not rebuilt when Android resumes it. */
internal class ImmersiveSceneMemory {
    var prepared: ImmersivePreparedScene? = null
}

private fun immersiveGradientFrame(
    key: String,
    mediaId: String,
    tone: ImmersiveArtworkTone,
    painter: Painter,
): ImmersiveGradientFrame = ImmersiveGradientFrame(
    key = key, mediaId = mediaId, edge = tone.edge, floor = tone.accent,
    imageUrl = requireNotNull(tone.displayUrl), landscape = tone.landscape,
    bottomTexture = tone.bottomTexture, nearWhiteBackground = tone.nearWhiteBackground,
    edgeBands = tone.edgeBands, colorField = tone.colorField,
    visibleTail = tone.visibleTail, diffusionTail = tone.diffusionTail,
    painter = painter,
)

/** Image-backed Immersion; metadata contrast is sampled from the prepared page. */
internal val ImmersiveControlInk = Color.White

internal data class ImmersiveMetadataInk(val title: Color, val artist: Color)

/** Change only metadata over near-white pixels; the rest of the controls stay white. */
internal fun immersiveMetadataInk(background: Color): ImmersiveMetadataInk {
    val nearWhite = background.luminance() >= 0.70f &&
        maxOf(background.red, background.green, background.blue) -
        minOf(background.red, background.green, background.blue) <= 0.16f
    return if (nearWhite) ImmersiveMetadataInk(Color(0xFF545451), Color(0xFF545451).copy(alpha = 0.82f))
        else ImmersiveMetadataInk(Color.White, Color.White.copy(alpha = 0.62f))
}

private fun immersiveMetadataBackground(bitmap: ImageBitmap?, fraction: Float): Color {
    if (bitmap == null) return IMMERSIVE_NEUTRAL_COLOR
    val image = bitmap.asAndroidBitmap()
    // Metadata can be moved in the editor. Sample its actual page position,
    // not the album's average brightness, once per texture/position change.
    return Color(image.getPixel(image.width * 3 / 10,
        (fraction * image.height).toInt().coerceIn(0, image.height - 1)))
}

/** A track opacity is applied AFTER the photo and its continuation are joined. */
@Composable
internal fun ImmersiveAudioScene(
    background: ImageBitmap,
    artworkHeight: Dp,
    sceneAlpha: () -> Float,
    artwork: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().graphicsLayer {
        compositingStrategy = CompositingStrategy.Auto
        alpha = sceneAlpha()
    }) {
        Image(
            bitmap = background, contentDescription = null,
            contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxWidth().height(artworkHeight + 1.dp)
                .clipToBounds()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithCache {
                    // The mask reveals a full image-backed blur underneath,
                    // rather than an independently coloured edge continuation.
                    val fadeMask = Brush.verticalGradient(
                        *Array(65) { index ->
                            val y = index / 64f
                            y to Color.White.copy(alpha = immersivePhotoFeatherAlpha(y))
                        },
                    )
                    val stageScrim = Brush.verticalGradient(
                        0.58f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.12f),
                    )
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = stageScrim)
                        drawRect(brush = fadeMask, blendMode = BlendMode.DstIn)
                    }
                },
        ) { artwork() }
    }
}

/** VIDEO never inherits album-derived wallpaper when its own ambient mode is off. */
internal fun immersivePageBaseColor(presentingVideo: Boolean, hasArtwork: Boolean, floor: Color): Color =
    if (presentingVideo) Color(0xFF111116)
    else if (hasArtwork) floor else IMMERSIVE_NEUTRAL_COLOR

/** Single optical handoff for both the VIDEO image and its entire page. */
internal fun capsuleImmersiveVideoBackplateAlpha(progress: Float): Float =
    1f - capsulePhotoOpacity(progress, CapsuleVideoBlendStyle.IMMERSION)

/** One cap for artwork and backdrop when a selected track has changed. */
internal const val IMMERSIVE_STALE_SCENE_FADE_MS = 420
internal const val IMMERSIVE_TRACK_FADE_MS = 380
internal val ImmersiveTrackEasing = CubicBezierEasing(0.25f, 0.12f, 0.60f, 1f)
internal fun immersiveSceneOpacity(
    displayedKey: String?,
    selectedKey: String,
    incomingAlpha: Float,
    staleAlpha: Float,
): Float = (if (displayedKey == selectedKey) incomingAlpha else staleAlpha)
    .coerceIn(0f, 1f)

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

private const val ImmersiveArtworkFraction = 0.60f

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
private val ImmersiveArtworkMax = 560.dp


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

/** Larger photographic stage, same controls budget and saved editor positions. */
internal fun immersiveControlsTop(available: Dp): Dp =
    (available * 0.55f).coerceIn(200.dp, 520.dp)

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
 * Light frames the artwork: a card with an edge, sitting in its own reserved space
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
    decodedArtworkCache: MutableMap<String, Pair<String, Painter>>,
    sceneMemory: ImmersiveSceneMemory,
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
    // Match Light: close the chooser rather than retarget it on song change.
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
        val controlsTop = immersiveControlsTop(maxHeight)
        // The host samples this exact visible crop once for the player and Lyrics.
        val artworkAspect = maxWidth.value / artworkHeight.value.coerceAtLeast(1f)
        val edge = artworkTone.edge
        // Keep the entire fade in the lower background's hue. Never mix in a vibrant
        // foreground accent (a green logo on white paper is not a green background).
        val floor = artworkTone.accent

        val entryScene = remember {
            sceneMemory.prepared?.takeIf { it.frame.mediaId == mediaMetadata.id }
        }
        val retainedCover = decodedArtworkCache[mediaMetadata.id]
            ?.takeIf { it.first == artworkTone.displayUrl }?.second
        var coverImageReady by remember(mediaMetadata.id, artworkTone.displayUrl) {
            mutableStateOf(retainedCover != null)
        }
        var decodedCoverPainter by remember(mediaMetadata.id, artworkTone.displayUrl) {
            mutableStateOf<Painter?>(retainedCover)
        }
        // A transient Coil error previously left the invisible preload mounted
        // forever with no new request until the entire player was reconstructed.
        // One retry is permitted per track/URL; a fresh track resets this budget.
        var coverLoadRetry by remember(mediaMetadata.id, artworkTone.displayUrl) {
            mutableIntStateOf(0)
        }
        val coverAndGradientReady = canRevealImmersiveArtwork(artworkTone, coverImageReady) &&
            decodedCoverPainter != null
        // The remembered frame contains the PREVIOUS song's actual image and all its
        // background colours. It remains intact during fade-out even if Media3 has already
        // switched its metadata to the NEXT song.
        val visualKey = if (!artworkTone.ready && entryScene?.frame?.mediaId == mediaMetadata.id)
            entryScene.frame.key else "${mediaMetadata.id}|${artworkTone.displayUrl}|${artworkAspect}"
        val restoredFrame = remember {
            entryScene?.frame ?: retainedCover?.takeIf {
                artworkTone.ready && artworkTone.displayUrl != null
            }?.let { immersiveGradientFrame(visualKey, mediaMetadata.id, artworkTone, it) }
        }
        var shownGradient by remember { mutableStateOf(restoredFrame) }
        // The last actual photo and page live underneath the next prepared
        // pair. Never fade to neutral, then mount a new bitmap 630ms later.
        var outgoingFrame by remember { mutableStateOf<ImmersiveGradientFrame?>(null) }
        val gradientAlpha = remember { Animatable(1f) }
        val staleSceneAlpha = remember { Animatable(1f) }
        var lastReadyFrame by remember { mutableStateOf(restoredFrame) }
        var outgoingStartAlpha by remember { mutableFloatStateOf(1f) }
        // The next track may take seconds to decode. Never show a PREVIOUS
        // song's gradient for that whole time. Fade both old photo and its
        // page together, then show the new track's lightweight placeholder.
        LaunchedEffect(shownGradient?.key, shownGradient?.key == visualKey) {
            val previous = shownGradient?.key
            if (previous == null || previous == visualKey) {
                staleSceneAlpha.snapTo(1f)
                return@LaunchedEffect
            }
            // A rapid next/next must not snap a half-visible photo to full
            // opacity. Complete the one finite dissolve; coalesce the pending
            // target rather than accumulating more full-screen image layers.
            if (outgoingFrame != null && gradientAlpha.isRunning) {
                snapshotFlow { gradientAlpha.isRunning }.first { !it }
            }
            staleSceneAlpha.snapTo(gradientAlpha.value)
            // Give a cached incoming full-size pair time to arrive before
            // dimming the old scene. The total stale lifetime is still bounded.
            delay(160L)
            staleSceneAlpha.animateTo(
                0f, tween(durationMillis = IMMERSIVE_STALE_SCENE_FADE_MS, easing = ImmersiveTrackEasing),
            )
            if (shownGradient?.key == previous && previous != visualKey) {
                shownGradient = null
                outgoingFrame = null
                lastReadyFrame = null
            }
        }
        LaunchedEffect(
            visualKey, coverAndGradientReady, edge, floor,
            artworkTone.bottomTexture, artworkTone.displayUrl, visible, isVideo,
        ) {
            if (!visible || !coverAndGradientReady ||
                shownGradient?.key == visualKey
            ) return@LaunchedEffect
            if (outgoingFrame != null && gradientAlpha.isRunning) {
                snapshotFlow { gradientAlpha.isRunning }.first { !it }
            }
            val readyPainter = decodedCoverPainter ?: return@LaunchedEffect
            // A frame whose continuation is still being prepared has never
            // appeared. Rapid A -> B -> C skips must retain A's matching pair.
            val old = lastReadyFrame?.takeIf { it.key == shownGradient?.key }
                ?: outgoingFrame
            outgoingStartAlpha = if (old?.key == outgoingFrame?.key)
                outgoingStartAlpha else staleSceneAlpha.value
            val geometryOnly = old?.mediaId == mediaMetadata.id
            outgoingFrame = old.takeUnless { geometryOnly }
            shownGradient = immersiveGradientFrame(visualKey, mediaMetadata.id, artworkTone, readyPainter)
            // New background + photo appear together only after their first
            // render has completed, instead of sequential fade-out/in.
            gradientAlpha.snapTo(if (isVideo || geometryOnly) 1f else 0f)
        }
        // Every cover uses the same photographic composition.
        val frame = shownGradient
        // A stalled full-resolution decode gets one bounded retry. A
        // 256px palette preview must never become the visible/outgoing photo.
        LaunchedEffect(mediaMetadata.id, artworkTone.displayUrl, coverLoadRetry, coverImageReady) {
            if (coverImageReady || !artworkTone.ready || artworkTone.displayUrl == null) return@LaunchedEffect
            delay(8_000L)
            if (!coverImageReady && coverLoadRetry < 1) coverLoadRetry++
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
        // Retained only for the older-Android VIDEO fallback below.
        val extensionPx = with(density) { 80.dp.toPx() }
            .coerceAtMost((pageHeightPx - coverHeightPx).coerceAtLeast(1f) * 0.38f)
        val tailFraction = (extensionPx / pageHeightPx.coerceAtLeast(1f))
            .coerceIn(0.02f, 0.08f)
        // One render result, one commit, one visual key. The previous design
        // had TWO independent render coroutines and a readiness callback:
        // one often won by a frame and the other faded in as a visible wave.
        val paintKey = remember(frame?.key, coverFraction) {
            "image-original-direction-v6|${frame?.key}|$coverFraction"
        }
        // One prepared page texture for one decoded cover. The previous
        // pair also built a separate tinted PHOTO overlay, which repeatedly
        // caused horizontal lines and made the real image look washed out.
        // In rapid A -> B -> C skips, the latest cached bitmap may belong
        // to A even if the most recently selected frame is B. Associate
        // the texture with its actual song so colours never linger as a
        // mismatched old-gradient layer underneath the new cover.
        var lastReadyPaint by remember {
            mutableStateOf(immersivePaintCache[paintKey]?.let { requireNotNull(frame).key to it }
                ?: entryScene?.let { it.frame.key to it.bitmap })
        }
        val outgoingPaint = remember(paintKey, outgoingFrame?.key) {
            lastReadyPaint?.takeIf { it.first == outgoingFrame?.key }?.second
        }
        var matchedPaint by remember(paintKey) {
            mutableStateOf(immersivePaintCache[paintKey] ?: entryScene?.takeIf {
                it.frame.key == frame?.key
            }?.bitmap)
        }
        LaunchedEffect(
            paintKey, frame?.colorField, pageFloor,
        ) {
            val readyFrame = frame ?: return@LaunchedEffect
            if (immersivePaintCache[paintKey] == null) matchedPaint = withContext(Dispatchers.Default) {
                val visible = readyFrame.colorField ?: CapsuleColorField(1, 1, listOf(readyFrame.floor))
                capsuleImmersiveImageBackdropBitmap(visible, coverFraction)
            }
            matchedPaint?.let {
                immersivePaintCache[paintKey] = it
                lastReadyPaint = readyFrame.key to it
                lastReadyFrame = readyFrame
                sceneMemory.prepared = ImmersivePreparedScene(readyFrame, paintKey, it)
            }
        }
        LaunchedEffect(frame?.key, matchedPaint, isVideo) {
            if (frame == null || matchedPaint == null ||
                frame.key != visualKey
            ) return@LaunchedEffect
            if (isVideo || gradientAlpha.value >= 0.999f) {
                gradientAlpha.snapTo(1f)
                outgoingFrame = null
            } else {
                // Every prepared scene gets a legible, soft reveal, even if
                // the previous cover already expired while decoding. Never
                // snap a newly loaded bright image straight onto the screen.
                gradientAlpha.animateTo(
                    1f, tween(durationMillis = IMMERSIVE_TRACK_FADE_MS, easing = ImmersiveTrackEasing),
                )
                outgoingFrame = null
            }
        }
        // The VIDEO window and its entire backdrop use the same spring.
        // Independent 220/280/290ms fades made the backing vanish while the
        // photo was still visible (or vice versa) on reverse transitions.
        val sharedVideoProgress = remember(mediaMetadata.id) {
            Animatable(if (isVideo) 1f else 0f)
        }
        var retainedVideoField by remember(mediaMetadata.id) {
            mutableStateOf(if (isVideo) videoAmbientField else null)
        }
        LaunchedEffect(isVideo, videoAmbientField) {
            if (isVideo) retainedVideoField = videoAmbientField
        }
        val immersiveInk = ImmersiveControlInk
        val metadataPageFraction = (with(density) { controlsTop.toPx() } + with(density) {
            ((immersivePositions[CapsuleLightBlock.METADATA] ?: 0f).dp + 20.dp).toPx()
        }) / pageHeightPx.coerceAtLeast(1f)
        val targetMetadataInk = remember(matchedPaint, metadataPageFraction) {
            immersiveMetadataInk(immersiveMetadataBackground(matchedPaint, metadataPageFraction))
        }
        val leavingMetadataInk = remember(outgoingPaint, metadataPageFraction) {
            immersiveMetadataInk(immersiveMetadataBackground(outgoingPaint, metadataPageFraction))
        }
        // Observe animation values in a derived state: the common white ->
        // white case must not recompose the entire player at display cadence.
        val metadataInks by remember(targetMetadataInk, leavingMetadataInk, frame?.key, visualKey) {
            derivedStateOf(structuralEqualityPolicy()) {
                val track = immersiveSceneOpacity(frame?.key, visualKey,
                    gradientAlpha.value, staleSceneAlpha.value)
                val video = capsuleImmersiveVideoBackplateAlpha(sharedVideoProgress.value)
                lerp(lerp(leavingMetadataInk.title, targetMetadataInk.title, track), Color.White, video) to
                    lerp(lerp(leavingMetadataInk.artist, targetMetadataInk.artist, track),
                        Color.White.copy(alpha = 0.62f), video)
            }
        }
        val metadataInk = metadataInks.first
        val metadataArtistInk = metadataInks.second
        val audioSceneVisible by remember(sharedVideoProgress) {
            derivedStateOf(structuralEqualityPolicy()) { sharedVideoProgress.value < 0.999f }
        }
        val videoLightVisible by remember(isVideo, sharedVideoProgress) {
            derivedStateOf(structuralEqualityPolicy()) { isVideo || sharedVideoProgress.value > 0.001f }
        }

        // Preload the NEXT image invisibly. Coil must finish decoding this exact URL
        // before the frame is allowed to replace the old one. It is never rendered early.
        // Once this exact artwork has decoded, dispose the invisible Coil
        // preloader. Keeping it alive while VIDEO runs doubled retained image
        // nodes and graphics layers for no visual benefit.
        if (capsuleImmersiveNeedsArtworkPreload(coverImageReady, artworkTone.ready, artworkTone.displayUrl)) {
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
                        artworkTone.displayUrl?.let { url ->
                            decodedArtworkCache.remove(mediaMetadata.id)
                            decodedArtworkCache[mediaMetadata.id] = url to success.painter
                            while (decodedArtworkCache.size > 3) {
                                decodedArtworkCache.remove(decodedArtworkCache.keys.first())
                            }
                        }
                    },
                    onError = { error ->
                        Timber.tag("CapsuleArtwork").w(error.result.throwable,
                            "immersive full-resolution decode failed: %s", mediaMetadata.id)
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

        // Compose each photo and its continuation into ONE scene before
        // applying track opacity. Independent alpha layers mix the old photo
        // with the new floor most strongly at the feather, drawing a stripe.
        Box(Modifier.fillMaxSize().background(IMMERSIVE_NEUTRAL_COLOR)) {
            if (audioSceneVisible) {
                val leaving = outgoingFrame
                if (leaving != null && outgoingPaint != null) {
                    ImmersiveAudioScene(
                        background = outgoingPaint,
                        artworkHeight = artworkHeight,
                        sceneAlpha = { outgoingStartAlpha },
                    ) {
                        val photoModifier = Modifier.fillMaxSize().graphicsLayer(
                            scaleX = if (leaving.landscape) 1.06f else 1f,
                            scaleY = if (leaving.landscape) 1.06f else 1f,
                        )
                        Image(
                            painter = leaving.painter, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = photoModifier,
                        )
                    }
                }
                matchedPaint?.let { bitmap ->
                    if (frame != null) ImmersiveAudioScene(
                        background = bitmap,
                        artworkHeight = artworkHeight,
                        sceneAlpha = {
                            immersiveSceneOpacity(
                                frame.key, visualKey, gradientAlpha.value, staleSceneAlpha.value,
                            )
                        },
                    ) {
                        Image(
                            painter = frame.painter,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer(
                                scaleX = if (frame.landscape) 1.06f else 1f,
                                scaleY = if (frame.landscape) 1.06f else 1f,
                            ),
                        )
                    }
                }
            }
            // VIDEO's one full-page opacity attenuates the whole audio
            // scene equally. Do not also fade its photo: multiplying two
            // opacities made the artwork disappear before its background.
            // VIDEO owns ONE neutral/ambient page above AUDIO, including
            // while its sampled frame is being prepared. The shared spring
            // controls this layer just like the VIDEO image itself.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = capsuleImmersiveVideoBackplateAlpha(sharedVideoProgress.value)
                }.background(Color(0xFF111116)),
            ) {
                val liveField = retainedVideoField
                if (liveField != null && videoLightVisible) {
                    key(mediaMetadata.id) {
                        CapsuleVideoAmbientBackdrop(
                            bands = if (isVideo) videoAmbientBands else emptyList(),
                            field = liveField,
                            animated = isVideo,
                            modifier = Modifier.fillMaxSize(),
                            fallbackFloor = Color(0xFF111116),
                            coverFraction = coverFraction,
                            tailFraction = tailFraction,
                            texture = frame?.bottomTexture ?: 0f,
                            landscape = frame?.landscape ?: false,
                            nearWhite = nearWhite,
                        )
                    }
                }
            }
        }

        CapsuleVideoArtworkTransition(
            presentingVideo = isVideo,
            needsSurface = needsVideoSurface,
            transitionKey = mediaMetadata.id,
            sharedProgress = sharedVideoProgress,
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
            // The stationary photo is already part of its full-page audio
            // scene. Only VIDEO geometry is owned by this transition.
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
                artworkHeight = controlsTop,
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
                    .padding(top = controlsTop)
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
                                widthFor = { if (it == CapsuleLightMetadataItem.FAVORITE) 48.dp else null },
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
                                                color = metadataInk,
                                                enabled = !immersiveEditEnabled,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (mediaMetadata.explicit) {
                                                    ExplicitBadge(
                                                        color = metadataArtistInk,
                                                    )
                                                    Spacer(Modifier.width(5.dp))
                                                }
                                                Text(
                                                    text =
                                                        artistCredits.joinToString { it.name },
                                                    color = metadataArtistInk,
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
                                                    .height(48.dp)
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
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            CapsuleFavoriteIcon(
                                                liked = liked,
                                                interactionSource = favoriteInteraction,
                                                tint =
                                                    if (liked) {
                                                        CapsuleFavoriteColors.selected(immersiveInk)
                                                    } else {
                                                        metadataInk
                                                    },
                                                modifier = Modifier.size(32.dp),
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
         * The rail at the foot of the screen. Light shows the same mark with the queue behind it,
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

