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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.nikhil.yt.innertube.toHighResThumbnail
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
private val ImmersiveQueueRailLift = 22.dp

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

    /*
     * One colour, used for both the floor under everything and the end of the cover's dissolve.
     * Being literally the same value is what makes the join invisible: there is no boundary where
     * two nearly-equal colours can disagree.
     *
     * Taken from the artwork and darkened, because a page in the cover's own colour is the point
     * of the design, and because the controls and their labels sit on it in the player's text
     * colour and have to stay readable whatever the cover is.
     */
    /*
     * Two colours, and the reason there are two.
     *
     * The cover has to end on the colour of its own last pixels or the join shows — that is edge,
     * measured off the foot of the artwork rather than taken from its palette, because a palette
     * reports what an image is about and the join cares about what it ends with.
     *
     * But a page in that colour is not always a page anyone can read: a cover ending in pale grey
     * would leave white text on white. So the page starts at edge, exactly where the cover left
     * off, and goes on darkening below it. Continuity at the seam, legibility by the time there
     * is anything to read.
     */
    val artworkColors = rememberCapsuleArtworkColors(mediaMetadata = mediaMetadata)
    val fallbackEdge =
        remember(artworkColors) { artworkColors.firstOrNull() ?: Color.Black }
    val edge = rememberImmersiveEdgeColor(mediaMetadata) ?: fallbackEdge
    val floor = remember(edge) { lerp(edge, Color.Black, 0.86f) }

    val chipSurface = textColor.copy(alpha = 0.10f)

    /*
     * Audio has one floor, the colour the cover dissolves into. Video does not: there is no cover
     * to take a colour from and nothing to dissolve, so the page is a plain dark gradient and the
     * only colour on the screen is the video's own.
     */
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val artworkHeight = immersiveArtworkHeight(maxHeight)

        /*
         * Where the cover ends, as a fraction of the sheet. The page has to be exactly edge at
         * that line and nowhere else, so the stop is computed rather than guessed.
         */
        val seam = (artworkHeight / maxHeight).coerceIn(0.05f, 0.95f)
        val settled = (seam + 0.34f).coerceAtMost(1f)

        val pageBackground =
            if (isVideo) {
                Brush.verticalGradient(listOf(Color(0xFF121212), Color.Black))
            } else {
                Brush.verticalGradient(
                    0f to edge,
                    seam to edge,
                    settled to floor,
                    1f to floor,
                )
            }

        Box(modifier = Modifier.fillMaxSize().background(pageBackground))

        Box(modifier = Modifier.fillMaxWidth().height(artworkHeight)) {
            if (isVideo) {
                /*
                 * A video is watched, not dissolved into a page. It keeps its own frame, black
                 * behind it, and none of the cover's gradient runs over it — the whole reason to
                 * switch to video is to see all of it.
                 */
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            player = playerConnection.player
                            useController = false
                            // Capsule shows its own loading state; a second spinner over the
                            // surface would be one indicator too many.
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            keepScreenOn = true
                        }
                    },
                    update = { playerView ->
                        if (playerView.player !== playerConnection.player) {
                            playerView.player = playerConnection.player
                        }
                    },
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                )
            } else {
                /*
                 * Crossfaded rather than swapped. The floor colour under it already eases from
                 * one track's to the next over 1.4 s, so a cover that changed in a single frame
                 * left the two halves of the same transition visibly out of step.
                 */
                AsyncImage(
                    model =
                        ImageRequest.Builder(LocalContext.current)
                            .data(mediaMetadata.thumbnailUrl?.toHighResThumbnail())
                            .crossfade(ImmersiveCoverCrossfadeMs)
                            .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                /*
                                 * Four stops, none of them abrupt. The two in the middle are what
                                 * keep the ramp from reading as an edge: alpha rises slowly while
                                 * there is still picture worth seeing and only crowds together
                                 * near the bottom, where there is nothing left to hide.
                                 */
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    ImmersiveFadeStart to Color.Transparent,
                                    0.72f to edge.copy(alpha = 0.22f),
                                    0.88f to edge.copy(alpha = 0.70f),
                                    1f to edge,
                                ),
                            ),
                )
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

                ImmersiveChip(background = chipSurface, onClick = onShowLyrics) {
                    Icon(
                        painter = painterResource(R.drawable.format_quote),
                        contentDescription = stringResource(R.string.lyrics),
                        tint = textColor,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Spacer(Modifier.size(10.dp))

                val favoriteInteraction = remember { MutableInteractionSource() }
                ImmersiveChip(
                    background = chipSurface,
                    interactionSource = favoriteInteraction,
                    onClick = onToggleLike,
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
                        modifier = Modifier.size(26.dp),
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

/** A round translucent seat for one icon, as the title row wears beside it. */
@Composable
private fun ImmersiveChip(
    background: Color,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
