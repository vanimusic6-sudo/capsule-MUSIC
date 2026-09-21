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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.extensions.toggleRepeatMode
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.playback.PlayerConnection
import com.nikhil.yt.playback.video.CapsulePlaybackMode
import com.nikhil.yt.playback.video.CapsuleVideoPhase
import com.nikhil.yt.ui.component.CapsuleFavoriteColors
import com.nikhil.yt.ui.component.CapsuleFavoriteIcon
import com.nikhil.yt.utils.makeTimeString

/** How much of the sheet the cover takes before it starts to go. */
private const val ImmersiveArtworkFraction = 0.52f

/**
 * Where inside the cover the dissolve begins, as a fraction of its height.
 *
 * A short ramp made a band: at four fifths the cover went from untouched to gone inside a fifth
 * of its height, and the eye reads that as a line drawn across the picture. Back to the longer,
 * softer dissolve, which is what the design was asking for in the first place — the picture does
 * not end anywhere, it stops being there.
 */
private const val ImmersiveFadeStart = 0.55f

/** Smallest cover that still reads as one; largest that still leaves the controls their room. */
private val ImmersiveArtworkMin = 200.dp
private val ImmersiveArtworkMax = 520.dp

/** As long as the floor colour's own ease, so cover and background arrive together. */
private const val ImmersiveCoverCrossfadeMs = 1_400

/** The grab rail near the bottom, which opens the queue. */
private val ImmersiveQueueRailWidth = 132.dp
private val ImmersiveQueueRailHeight = 5.dp

/** How far the rail sits off the foot of the sheet, rather than against it. */
private val ImmersiveQueueRailLift = 44.dp

/**
 * How tall the cover is in a sheet of this height.
 *
 * Clamped at both ends. A very short window must not be handed a cover that leaves no room for
 * the controls, and a very tall one must not be handed a cover that runs past the sheet — and
 * neither end may produce a height that a later layout pass could read as negative.
 */
internal fun immersiveArtworkHeight(available: Dp): Dp =
    (available * ImmersiveArtworkFraction).coerceIn(ImmersiveArtworkMin, ImmersiveArtworkMax)

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

    // The user has already selected VIDEO during RESOLVING. Reserve the final video
    // geometry immediately, instead of briefly drawing the old square audio artwork
    // and moving the controls only after the stream enters PLAYING.
    val presentingVideo =
        isVideo ||
            (videoPlaybackState.preferredMode == CapsulePlaybackMode.VIDEO &&
                videoPlaybackState.phase == CapsuleVideoPhase.RESOLVING)

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
        // The reveal is scoped to the selected song. A newly selected 16:9 cover must
        // start at zero instead of inheriting the previous cover's alpha for one frame.
        val artworkReveal = key(mediaMetadata.id, mediaMetadata.thumbnailUrl, artworkAspect) {
            val alpha by animateFloatAsState(
                targetValue = if (coverAndGradientReady) 1f else 0f,
                animationSpec = tween(durationMillis = ImmersiveCoverCrossfadeMs),
                label = "immersivePreparedArtworkReveal",
            )
            alpha
        }

        /*
         * Where the cover ends, as a fraction of the sheet. The page has to be exactly edge at
         * that line and nowhere else, so the stop is computed rather than guessed.
         */
        val seam = (artworkHeight / maxHeight).coerceIn(0.05f, 0.95f)
        val settled = (seam + 0.34f).coerceAtMost(1f)

        val pageBackground =
            if (presentingVideo) {
                Brush.verticalGradient(listOf(IMMERSIVE_NEUTRAL_COLOR, Color(0xFF1E1E1E)))
            } else {
                Brush.verticalGradient(
                    0f to edge,
                    seam to edge,
                    settled to floor,
                    1f to floor,
                )
            }

        // Both image AND gradient are neutral until the final image is decoded. Once ready,
        // dissolve the whole prepared pair together rather than showing a partly cropped
        // bright preview over a grey background while its palette is still loading.
        Box(modifier = Modifier.fillMaxSize().background(IMMERSIVE_NEUTRAL_COLOR))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = if (presentingVideo) 1f else artworkReveal)
                .background(pageBackground),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(artworkHeight)
                    .clipToBounds()
                    /*
                     * The cover opens the words, as it does in the other two designs. A button
                     * for it was a button this screen did not need: the cover is the largest
                     * thing on it and the gesture is already the app's own.
                     *
                     * Not while a video is playing — there the frame is the thing being watched,
                     * and a tap that replaced it with lyrics would be a trap.
                     */
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !presentingVideo,
                        onClick = onShowLyrics,
                    ),
        ) {
            if (presentingVideo) {
                // The 16:9 card has the same horizontal inset and centred placement as
                // standard Capsule players. Keep its bounds through RESOLVING so the
                // first video frame cannot move the controls or briefly show audio art.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = videoFrameTop, start = videoSidePadding, end = videoSidePadding)
                        .fillMaxWidth()
                        .height(videoFrameHeight)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.Black),
                ) {
                    if (isVideo) {
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
            } else {
                /*
                 * Crossfaded rather than swapped. The floor colour under it already eases from
                 * one track's to the next over 1.4 s, so a cover that changed in a single frame
                 * left the two halves of the same transition visibly out of step.
                 */
                // A *single* centre-cropped image: FillBounds distorted the video into a
                // second, elongated frame above the real picture. Fit left letterboxed
                // padding, so we zoom in uniformly from the centre instead. It is the
                // artwork itself that enlarges; no stretched duplicate is drawn behind it.
                // Never submit the original video thumbnail before crop dimensions and the
                // matching background are known. Coil's display decode can finish after the
                // palette's smaller sampling decode, so keep the actual pixels transparent
                // until onSuccess confirms that the final selected image is ready.
                if (artworkTone.ready && artworkTone.displayUrl != null) {
                    val artworkRequest =
                        ImageRequest.Builder(LocalContext.current)
                            .data(artworkTone.displayUrl)
                            // A single coordinated reveal avoids a second, asynchronous Coil
                            // crossfade whose intermediate frame could still show black bars.
                            .crossfade(false)
                            .build()
                    AsyncImage(
                        model = artworkRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onSuccess = { coverImageReady = true },
                        onError = { coverImageReady = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = if (artworkTone.landscape) 1.06f else 1f,
                                scaleY = if (artworkTone.landscape) 1.06f else 1f,
                                alpha = artworkReveal,
                            ),
                    )

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer(alpha = artworkReveal)
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        (if (artworkTone.landscape) 0.27f else 0.42f) to Color.Transparent,
                                        (if (artworkTone.landscape) 0.49f else 0.60f) to edge.copy(alpha = 0.46f),
                                        (if (artworkTone.landscape) 0.69f else 0.77f) to edge.copy(alpha = 0.91f),
                                        (if (artworkTone.landscape) 0.84f else 0.91f) to edge,
                                        1f to edge,
                                    ),
                                ),
                    )
                }
            }
        }

        /*
         * The controls follow the cover instead of hanging off the bottom of the window. Pinned
         * low they drifted away from the picture they belong to and left a hole in the middle of
         * the screen; sitting directly under the cover, the two read as one object and the empty
         * space collects at the foot, where the rail is.
         */
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = artworkHeight)
                    .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
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
                    // The explicit mark reads on the artist line here exactly as it does in the
                    // other designs, and stays outside the artist's tap target: it marks the
                    // track, so opening an artist from it would be a lie about what was pressed.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mediaMetadata.explicit) {
                            ExplicitBadge(color = textColor.copy(alpha = 0.62f))
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = mediaMetadata.artists.joinToString { it.name },
                            color = textColor.copy(alpha = 0.62f),
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = navigableArtists.isNotEmpty(),
                                ) {
                                    when (navigableArtists.size) {
                                        1 -> onArtistSelected(navigableArtists.first())
                                        else -> showArtistPicker = true
                                    }
                                },
                        )
                    }
                }

                /*
                 * The heart sits on the page rather than on a disc of its own. With the lyrics
                 * button gone there is nothing beside it for a seat to group it with, and a
                 * single chip in the corner reads as a leftover.
                 */
                val favoriteInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier =
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = favoriteInteraction,
                                indication = null,
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

            Spacer(Modifier.height(20.dp))

            CapsuleThinSlider(
                value = shownPosition.toFloat(),
                valueRange = 0f..safeDuration.coerceAtLeast(1L).toFloat(),
                enabled = safeDuration > 0L,
                activeColor = textColor,
                inactiveColor = textColor.copy(alpha = 0.22f),
                onValueChange = { onSeekPreview(it.toLong()) },
                onValueChangeFinished = onSeekFinished,
                modifier = Modifier.fillMaxWidth().height(22.dp),
                trackHeight = 8.dp,
                thumbRadius = 5.dp,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(makeTimeString(shownPosition), color = textColor.copy(alpha = 0.55f), fontSize = 13.sp)
                Text(makeTimeString(safeDuration), color = textColor.copy(alpha = 0.55f), fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.shuffle),
                        stringResource(R.string.shuffle),
                        tint = textColor.copy(alpha = if (shuffleEnabled) 1f else 0.5f),
                        modifier = Modifier.size(21.dp),
                    )
                }

                CapsuleAudioVideoToggle(
                    lightStyle = true,
                    state = videoPlaybackState,
                    textColor = textColor,
                    enabled = true,
                    onAudioClick = {
                        playerConnection.service.setCapsulePlaybackMode(CapsulePlaybackMode.AUDIO)
                    },
                    onVideoClick = {
                        playerConnection.service.setCapsulePlaybackMode(CapsulePlaybackMode.VIDEO)
                    },
                    modifier = Modifier.weight(1f),
                )

                IconButton(
                    onClick = { showSleepTimerDialog = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.bedtime),
                        stringResource(R.string.sleep_timer),
                        tint = textColor.copy(alpha = if (sleepTimerEnabled) 1f else 0.5f),
                        modifier = Modifier.size(21.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Capsule Light's transport panel, reused rather than redrawn: one panel, one place to
            // change it, and no chance of the two designs drifting apart.
            CapsuleLightControls(
                textColor = textColor,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                enabled = true,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                onShuffle = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
                onPrevious = { playerConnection.player.seekToPrevious() },
                onNext = { playerConnection.player.seekToNext() },
                onRepeat = { playerConnection.player.toggleRepeatMode() },
                orbit = {
                    CapsuleOrbitButton(
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        visible = visible,
                        color = textColor,
                    ) { playerConnection.player.togglePlayPause() }
                },
                onMenuClick = onMenuClick,
            )

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
                    .height(34.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
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

