 /** Capsule MUSIC
 *
 * Capsule full-player skin ported from the original Capsule repository.
 * Visual structure, dimensions and gestures are kept from the donor.
 * Playback is still entirely owned by Velune PlayerConnection.
 *
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsuleLightLyricLineKey
import com.nikhil.yt.constants.CapsuleLightEditEnabledKey
import com.nikhil.yt.constants.CapsuleLightEditSessionActiveKey
import com.nikhil.yt.constants.CapsuleLightLayoutOrderKey
import com.nikhil.yt.constants.CapsuleLightMetadataOrderKey
import com.nikhil.yt.constants.CapsuleLightModeOrderKey
import com.nikhil.yt.constants.CapsuleLightAvOrderKey
import com.nikhil.yt.constants.CapsuleLightTransportOrderKey
import com.nikhil.yt.constants.CapsuleLightArtworkWidthScaleKey
import com.nikhil.yt.constants.CapsuleLightArtworkHeightScaleKey
import com.nikhil.yt.constants.CapsuleLightBlockGapsKey
import com.nikhil.yt.constants.CapsuleLightCanvasPositionsKey
import com.nikhil.yt.constants.LyricsSyncOffsetKey
import com.nikhil.yt.constants.CapsulePlayerDesign
import com.nikhil.yt.db.entities.LyricsEntity
import com.nikhil.yt.ui.component.ArtistSelectionItem
import com.nikhil.yt.constants.CropThumbnailToSquareKey
import com.nikhil.yt.constants.HidePlayerThumbnailKey
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.extensions.toggleRepeatMode
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.playback.PlayerConnection
import com.nikhil.yt.playback.video.CapsulePlaybackMode
import com.nikhil.yt.playback.video.CapsuleVideoPhase
import com.nikhil.yt.together.TogetherRole
import com.nikhil.yt.together.TogetherSessionState
import com.nikhil.yt.utils.makeTimeString
import com.nikhil.yt.ui.menu.clampOffset
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val CapsuleArtworkShape =
    RoundedCornerShape(24.dp)

private val CapsuleControlsShape =
    RoundedCornerShape(24.dp)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CapsulePlayerContent(
    design: CapsulePlayerDesign,
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
    onExpandQueue: () -> Unit,
    onArtworkClick: () -> Unit,
    onArtistSelected: (MediaMetadata.Artist) -> Unit,
    onMenuClick: () -> Unit,
    onCollapse: () -> Unit,
    bottomPadding: Dp,
    /**
     * Whether the player is open — anywhere above the collapsed anchor, not only fully expanded.
     *
     * This content stays composed behind the collapsed sheet, so anything it does while closed is
     * work nobody can see: no lyrics flow collected, nothing parsed, no lyrics requested, and no
     * orbit clock running.
     *
     * Deliberately "not collapsed" rather than "expanded". A strict expanded flag flips at the
     * anchor, which is the end of the open animation and the start of the close one, so the lyric
     * row would pop in late and vanish early and the layout would visibly reset on both.
     */
    open: Boolean = true,
    motionActive: Boolean = true,
    expansionProgress: Float = if (open) 1f else 0f,
) {
    // Keep the full player alive through its collapse/open transition, but once the sheet has
    // actually reached the mini-player anchor there is no reason to keep its subscriptions,
    // artwork tree and local animation state composed underneath.
    val onScreen = appIsOnScreen()
    if (!onScreen || (!open && expansionProgress <= 0.001f)) {
        Box(Modifier.fillMaxSize())
        return
    }

    val visible = open
    val isLight = design == CapsulePlayerDesign.LIGHT
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val (lightEditorEnabled, onLightEditorEnabledChange) =
        rememberPreference(
            CapsuleLightEditEnabledKey,
            defaultValue = false,
        )
    val (lightOrderEncoded, onLightOrderEncodedChange) =
        rememberPreference(
            CapsuleLightLayoutOrderKey,
            defaultValue = CapsuleLightBaseOrderEncoded,
        )
    val (lightEditSessionActive, onLightEditSessionActiveChange) =
        rememberPreference(
            CapsuleLightEditSessionActiveKey,
            defaultValue = false,
        )

    var lightOrder by
        remember(lightOrderEncoded) {
            mutableStateOf(decodeCapsuleLightOrder(lightOrderEncoded))
        }

    val (lightMetadataOrderEncoded, onLightMetadataOrderEncodedChange) =
        rememberPreference(
            CapsuleLightMetadataOrderKey,
            defaultValue = CapsuleLightMetadataBaseOrderEncoded,
        )
    val (lightModeOrderEncoded, onLightModeOrderEncodedChange) =
        rememberPreference(
            CapsuleLightModeOrderKey,
            defaultValue = CapsuleLightModeBaseOrderEncoded,
        )
    val (lightAvOrderEncoded, onLightAvOrderEncodedChange) =
        rememberPreference(
            CapsuleLightAvOrderKey,
            defaultValue = CapsuleLightAvBaseOrderEncoded,
        )
    val (lightTransportOrderEncoded, onLightTransportOrderEncodedChange) =
        rememberPreference(
            CapsuleLightTransportOrderKey,
            defaultValue = CapsuleLightTransportBaseOrderEncoded,
        )
    val (savedArtworkWidthScale, onArtworkWidthScaleChange) =
        rememberPreference(
            CapsuleLightArtworkWidthScaleKey,
            defaultValue = 1f,
        )
    val (savedArtworkHeightScale, onArtworkHeightScaleChange) =
        rememberPreference(
            CapsuleLightArtworkHeightScaleKey,
            defaultValue = 1f,
        )

    val (lightBlockGapsEncoded, onLightBlockGapsEncodedChange) =
        rememberPreference(
            CapsuleLightBlockGapsKey,
            defaultValue = CapsuleLightBaseGapsEncoded,
        )

    val (lightCanvasPositionsEncoded, onLightCanvasPositionsEncodedChange) =
        rememberPreference(
            CapsuleLightCanvasPositionsKey,
            defaultValue = CapsuleLightCanvasPositionsBaseEncoded,
        )

    var lightMetadataOrder by
        remember(lightMetadataOrderEncoded) {
            mutableStateOf(decodeCapsuleLightMetadataOrder(lightMetadataOrderEncoded))
        }
    var lightModeOrder by
        remember(lightModeOrderEncoded) {
            mutableStateOf(decodeCapsuleLightModeOrder(lightModeOrderEncoded))
        }
    var lightAvOrder by
        remember(lightAvOrderEncoded) {
            mutableStateOf(decodeCapsuleLightAvOrder(lightAvOrderEncoded))
        }
    var lightTransportOrder by
        remember(lightTransportOrderEncoded) {
            mutableStateOf(decodeCapsuleLightTransportOrder(lightTransportOrderEncoded))
        }
    var lightArtworkWidthScale by
        remember(savedArtworkWidthScale) {
            mutableFloatStateOf(savedArtworkWidthScale.coerceIn(0.55f, 1.08f))
        }
    var lightArtworkHeightScale by
        remember(savedArtworkHeightScale) {
            mutableFloatStateOf(savedArtworkHeightScale.coerceIn(0.55f, 1.35f))
        }

    var lightBlockGaps by
        remember(lightBlockGapsEncoded) {
            mutableStateOf(decodeCapsuleLightBlockGaps(lightBlockGapsEncoded))
        }

    var lightCanvasPositions by
        remember(lightCanvasPositionsEncoded) {
            mutableStateOf(decodeCapsuleLightCanvasPositions(lightCanvasPositionsEncoded))
        }

    // Portrait Light uses the v2 canvas. Landscape intentionally falls back to the untouched
    // fixed Light stack until a dedicated horizontal composition is designed.
    val useClayLayout = isLight && !isLandscape

    /*
     * Crash recovery is tied to an edit transaction, not to the editor toggle itself. A completed
     * layout may stay editable across normal app restarts. Only a process that disappears while a
     * drag/new layout is still unvalidated leaves the persistent transaction bit behind.
     */
    var lightEditInProgress by remember { mutableStateOf(false) }
    var lightValidationGeneration by remember { mutableStateOf(0) }
    var artworkResizeActive by remember { mutableStateOf(false) }
    var artworkSelectionResetToken by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (
            CapsuleLightEditorProcessGuard.shouldCheckRecovery() &&
            lightEditSessionActive
        ) {
            lightOrder = CapsuleLightBaseOrder
            lightMetadataOrder = CapsuleLightMetadataBaseOrder
            lightModeOrder = CapsuleLightModeBaseOrder
            lightAvOrder = CapsuleLightAvBaseOrder
            lightTransportOrder = CapsuleLightTransportBaseOrder
            lightArtworkWidthScale = 1f
            lightArtworkHeightScale = 1f
            lightBlockGaps = CapsuleLightBaseGaps
            lightCanvasPositions = emptyMap()

            onLightOrderEncodedChange(CapsuleLightBaseOrderEncoded)
            onLightMetadataOrderEncodedChange(CapsuleLightMetadataBaseOrderEncoded)
            onLightModeOrderEncodedChange(CapsuleLightModeBaseOrderEncoded)
            onLightAvOrderEncodedChange(CapsuleLightAvBaseOrderEncoded)
            onLightTransportOrderEncodedChange(CapsuleLightTransportBaseOrderEncoded)
            onArtworkWidthScaleChange(1f)
            onArtworkHeightScaleChange(1f)
            onLightBlockGapsEncodedChange(CapsuleLightBaseGapsEncoded)
            onLightCanvasPositionsEncodedChange(CapsuleLightCanvasPositionsBaseEncoded)
            onLightEditorEnabledChange(false)
            onLightEditSessionActiveChange(false)
        }
    }

    LaunchedEffect(lightValidationGeneration, lightEditInProgress) {
        if (lightValidationGeneration > 0 && !lightEditInProgress) {
            // Surviving several frames after the reordered tree was composed marks it as safe.
            kotlinx.coroutines.delay(1_200L)
            if (!lightEditInProgress) {
                onLightEditSessionActiveChange(false)
            }
        }
    }

    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val isPlaying by
        playerConnection.isPlaying.collectAsState()

    val playbackState by
        playerConnection.playbackState.collectAsState()

    val playbackError by
        playerConnection.error.collectAsState()

    val canSkipPrevious by
        playerConnection.canSkipPrevious.collectAsState()

    val canSkipNext by
        playerConnection.canSkipNext.collectAsState()

    val repeatMode by
        playerConnection.repeatMode.collectAsState()

    val togetherState by
        playerConnection.service.togetherSessionState.collectAsState()

    val videoPlaybackState by
        playerConnection.service.videoPlaybackState.collectAsState()

    val isCapsuleVideoPlaying =
        videoPlaybackState.mode == CapsulePlaybackMode.VIDEO &&
            videoPlaybackState.phase == CapsuleVideoPhase.PLAYING

    LaunchedEffect(isCapsuleVideoPlaying) {
        if (isCapsuleVideoPlaying && artworkResizeActive) {
            // Switching mode cancels an in-flight artwork resize transaction. The last committed
            // artwork size remains authoritative; VIDEO must never leave a stale edit lock behind.
            artworkResizeActive = false
            lightEditInProgress = false
            lightValidationGeneration += 1
        }
    }

    val isListenTogetherGuest =
        (togetherState as? TogetherSessionState.Joined)
            ?.role is TogetherRole.Guest

    val hideArtwork by
        rememberPreference(
            HidePlayerThumbnailKey,
            defaultValue = false,
        )

    val cropAlbumArt by
        rememberPreference(
            CropThumbnailToSquareKey,
            defaultValue = false,
        )

    val isLoading =
        playbackState ==
            Player.STATE_BUFFERING

    val canSeek =
        !isListenTogetherGuest &&
            playerConnection.player
                .isCommandAvailable(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                )

    val secondaryText =
        textColor.copy(
            alpha = 0.55f,
        )

    val outline =
        textColor.copy(
            alpha = 0.16f,
        )

    val panel =
        textColor.copy(
            alpha = 0.025f,
        )

    val displayPosition =
        (sliderPosition ?: positionMs)
            .coerceAtLeast(0L)

    // Capsule Light draws the sounding line under the artwork card, unless it is switched off.
    val showLyricLine by
        rememberPreference(
            CapsuleLightLyricLineKey,
            defaultValue = true,
        )

    /*
     * Visibility controls the lyric work, not its geometry. If the feature is enabled, its slot
     * remains part of the Light scene while another surface (such as full Lyrics) temporarily owns
     * the screen. If the feature itself is disabled, the slot is truly zero-height outside edit
     * mode so it cannot steal artwork budget.
     */
    val lyricLineConfigured = isLight && showLyricLine
    val lyricLineActive = lyricLineConfigured && visible && motionActive

    val lyricsEntity by
        if (lyricLineActive) {
            playerConnection.currentLyrics.collectAsState(initial = null)
        } else {
            remember { mutableStateOf<LyricsEntity?>(null) }
        }

    val syncedLyricLines =
        remember(lyricsEntity) { capsuleLightLyricLines(lyricsEntity?.lyrics) }

    // The same correction the lyrics screen applies: one song, one answer about its timing.
    val lyricSyncOffsetMs by rememberPreference(LyricsSyncOffsetKey, defaultValue = 0)

    /*
     * The line has to ask for the lyrics itself. Fetching used to be the lyrics screen's job, so a
     * track whose lyrics screen was never opened had nothing in the database and the row stayed
     * empty forever, waiting on a request nobody was going to make.
     */
    if (lyricLineActive) {
        RequestLyricsIfMissing(mediaMetadata, lyricsEntity)
    }

    val safeDuration =
        durationMs
            .takeIf {
                it > 0L &&
                    it != C.TIME_UNSET
            }
            ?: 0L

    val remaining =
        (
            safeDuration -
                displayPosition
        ).coerceAtLeast(0L)

    val density =
        LocalDensity.current

    val swipeThresholdPx =
        with(density) {
            64.dp.toPx()
        }

    val artistCredits = rememberArtistCredits(mediaMetadata.artists)
    val navigableArtists = rememberNavigableArtists(artistCredits)

    var showArtistPicker by
        remember {
            mutableStateOf(false)
        }

    fun handleArtistClick() {
        when {
            navigableArtists.isEmpty() -> Unit
            artistCredits.size == 1 && artistCredits.first().id != null ->
                onArtistSelected(navigableArtists.first())
            else -> showArtistPicker = true
        }
    }

    var showSleepTimerDialog by
        remember {
            mutableStateOf(false)
        }

    var sleepTimerValue by
        remember {
            mutableFloatStateOf(30f)
        }

    val sleepTimerEnabled =
        remember(
            playerConnection.service
                .sleepTimer
                .triggerTime,
            playerConnection.service
                .sleepTimer
                .pauseWhenSongEnd,
        ) {
            playerConnection.service
                .sleepTimer
                .isActive
        }

    if (showArtistPicker) {
        CapsuleArtistPickerDialog(
            artists = artistCredits,
            onDismiss = { showArtistPicker = false },
            onArtistSelected = onArtistSelected,
        )
    }

    if (showSleepTimerDialog) {
        CapsuleSleepTimerDialog(
            minutes = sleepTimerValue,
            enabled = !isListenTogetherGuest,
            active = sleepTimerEnabled,
            onMinutesChange = { sleepTimerValue = it },
            onEndOfSong = {
                if (!isListenTogetherGuest) playerConnection.service.sleepTimer.start(-1)
                showSleepTimerDialog = false
            },
            onClear = {
                if (!isListenTogetherGuest) playerConnection.service.sleepTimer.clear()
                showSleepTimerDialog = false
            },
            onConfirm = {
                if (!isListenTogetherGuest) playerConnection.service.sleepTimer.start(sleepTimerValue.toInt())
                showSleepTimerDialog = false
            },
            onDismiss = { showSleepTimerDialog = false },
        )
    }

    val onPlayPause: () -> Unit = {
        if (!isListenTogetherGuest) {
            if (playbackState == Player.STATE_ENDED) {
                playerConnection.player.seekTo(0, 0)
                playerConnection.player.playWhenReady = true
            } else {
                playerConnection.player.togglePlayPause()
            }
        }
    }

    val artworkContent: @Composable () -> Unit = {
            val mediaShape =
                if (isCapsuleVideoPlaying) {
                    RoundedCornerShape(28.dp)
                } else {
                    if (isLight) RoundedCornerShape(16.dp) else CapsuleArtworkShape
                }
            val currentPlaybackError = playbackError

            if (currentPlaybackError != null) {
                PlaybackError(
                    error = currentPlaybackError,
                    retry = playerConnection.service::retryCurrentFromFreshStream,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .then(
                                if (useClayLayout && !isCapsuleVideoPlaying) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(
                                            if (isCapsuleVideoPlaying) 16f / 9f else 1f,
                                        )
                                },
                            )
                            .offset(
                                y = if (isCapsuleVideoPlaying || isLight) 0.dp else (-5).dp,
                            )
                            .clip(mediaShape)
                            .border(
                                1.dp,
                                if (isLight) Color.Transparent else outline,
                                mediaShape,
                            )
                            .background(
                                if (isCapsuleVideoPlaying && visible) {
                                    Color.Black
                                } else {
                                    textColor.copy(alpha = 0.045f)
                                },
                            )
                            .clickable(
                                enabled = !isCapsuleVideoPlaying && !(isLight && lightEditorEnabled),
                                onClick = onArtworkClick,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCapsuleVideoPlaying) {
                        AndroidView(
                            factory = { viewContext ->
                                PlayerView(viewContext).apply {
                                    // Disable Media3's built-in controls BEFORE binding
                                    // the player: binding can schedule their initial show.
                                    useController = false
                                    player = playerConnection.player
                                    hideController()

                                    /*
                                     * Capsule already shows its own VIDEO loading
                                     * state in the player controls, so Media3's
                                     * built-in buffering spinner is deliberately
                                     * disabled to avoid a second indicator over
                                     * the video surface.
                                     */
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)

                                    /*
                                     * Fill the entire Capsule video frame while
                                     * preserving the source aspect ratio.
                                     * ZOOM crops only the overflowing edges instead
                                     * of stretching the image or leaving letterbox
                                     * gaps above/below.
                                     */
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    keepScreenOn = true
                                }
                            },
                            update = { playerView ->
                                // Keep the embedded renderer control-free across recompositions.
                                playerView.useController = false
                                if (playerView.player !== playerConnection.player) {
                                    playerView.player = playerConnection.player
                                }
                                playerView.hideController()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (hideArtwork) {
                        Icon(
                            painter = painterResource(R.drawable.album),
                            contentDescription = mediaMetadata.title,
                            tint = secondaryText,
                            modifier = Modifier.size(72.dp),
                        )
                    } else {
                        AsyncImage(
                            model = mediaMetadata.thumbnailUrl?.let { artwork ->
                                // YouTube's =w540 URL rewrite corrupts SoundCloud CDN URLs
                                // with query parameters; their full-size art is selected at search.
                                if (mediaMetadata.id.startsWith("soundcloud:")) artwork
                                else artwork.toHighResThumbnail()
                            },
                            contentDescription = mediaMetadata.title,
                            contentScale =
                                if (
                                    cropAlbumArt ||
                                    (
                                        useClayLayout &&
                                            (
                                                artworkResizeActive ||
                                                    abs(lightArtworkWidthScale - 1f) > 0.01f ||
                                                    abs(lightArtworkHeightScale - 1f) > 0.01f
                                            )
                                    )
                                ) {
                                    // Once the user reshapes the artwork frame, the image follows
                                    // that frame by cropping instead of leaving Fit letterboxing.
                                    ContentScale.Crop
                                } else {
                                    ContentScale.Fit
                                },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
    }

    /*
     * A disabled lyric line is not part of the editable scene at all. Keep its persisted order and
     * last position in storage so enabling it later restores the user's design, but remove it from
     * the live CanvasV2 order/positions. This also removes the otherwise invisible inter-block gap,
     * so the artwork really receives every pixel that the disabled row used to consume.
     */
    val visibleLightOrder =
        if (lyricLineConfigured) {
            lightOrder
        } else {
            lightOrder.filterNot { it == CapsuleLightBlock.LYRIC }
        }
    val visibleLightCanvasPositions =
        if (lyricLineConfigured) {
            lightCanvasPositions
        } else {
            lightCanvasPositions - CapsuleLightBlock.LYRIC
        }

    fun restoreHiddenLyric(
        reorderedVisible: List<CapsuleLightBlock>,
    ): List<CapsuleLightBlock> {
        if (lyricLineConfigured || CapsuleLightBlock.LYRIC in reorderedVisible) {
            return decodeCapsuleLightOrder(
                encodeCapsuleLightOrder(reorderedVisible),
            )
        }

        val storedIndex =
            lightOrder
                .indexOf(CapsuleLightBlock.LYRIC)
                .takeIf { it >= 0 }
                ?: 1
        val full =
            reorderedVisible
                .toMutableList()
                .apply {
                    add(
                        storedIndex.coerceIn(0, size),
                        CapsuleLightBlock.LYRIC,
                    )
                }

        return decodeCapsuleLightOrder(
            encodeCapsuleLightOrder(full),
        )
    }

    CapsulePlayerLayout(
        design = design,
        textColor = textColor,
        onCollapse = onCollapse,
        onMenuClick = onMenuClick,
        onExpandQueue = onExpandQueue,
        lyricLine =
            if (!lyricLineConfigured) {
                null
            } else {
                {
                    /*
                     * positionMs already ticks for the progress bar, so following the
                     * lyrics adds no timer of its own. When the player is temporarily hidden, keep
                     * this composable only as geometry and stop lyric collection/fetching above.
                     */
                    CapsuleLightLyricLine(
                        line =
                            if (lyricLineActive) {
                                capsuleLightLyricLineAt(
                                    syncedLyricLines,
                                    displayPosition + clampOffset(lyricSyncOffsetMs),
                                )
                            } else {
                                null
                            },
                        textColor = textColor,
                    )
                }
            },
        modifier =
            Modifier
                .fillMaxSize()
                // Keep the player in exactly the same geometry while Immersive temporarily
                // hides the status bar; visibility itself must not become a layout signal.
                .windowInsetsPadding(
                    WindowInsets.systemBarsIgnoringVisibility.only(
                        WindowInsetsSides.Top +
                            WindowInsetsSides.Horizontal +
                            WindowInsetsSides.Bottom,
                    ),
                )
                .padding(
                    bottom =
                        bottomPadding + 4.dp,
                )
                /*
                 * Original Capsule gesture:
                 * swipe DOWN by 64 dp anywhere on the player to open Queue.
                 */
                .pointerInput(
                    onExpandQueue,
                    swipeThresholdPx,
                    isLight,
                    lightEditorEnabled,
                ) {
                    // Reordering owns vertical drags while the Light editor is active.
                    if (isLight && lightEditorEnabled) return@pointerInput

                    var accumulated =
                        0f

                    var opened =
                        false

                    detectVerticalDragGestures(
                        onDragStart = {
                            accumulated =
                                0f

                            opened =
                                false
                        },
                        onVerticalDrag = {
                                change,
                                dragAmount,
                            ->
                            if (
                                dragAmount >
                                0f
                            ) {
                                accumulated +=
                                    dragAmount
                            } else {
                                accumulated =
                                    (
                                        accumulated +
                                            dragAmount
                                    ).coerceAtLeast(
                                        0f,
                                    )
                            }

                            if (
                                !opened &&
                                accumulated >=
                                swipeThresholdPx
                            ) {
                                change.consume()

                                opened =
                                    true

                                onExpandQueue()
                            }
                        },
                        onDragEnd = {
                            accumulated =
                                0f

                            opened =
                                false
                        },
                        onDragCancel = {
                            accumulated =
                                0f

                            opened =
                                false
                        },
                    )
                },
        lightEditorEnabled = useClayLayout && lightEditorEnabled,
        lightOrder = visibleLightOrder,
        lightCanvasPositionsDp = visibleLightCanvasPositions,
        lightGapsDp = lightBlockGaps,
        onLightOrderChange = { reordered ->
            lightOrder = restoreHiddenLyric(reordered)
        },
        onLightEditStarted = {
            lightEditInProgress = true
            onLightEditSessionActiveChange(true)
        },
        onLightArtworkSelectionDismiss = {
            artworkSelectionResetToken += 1
        },
        lightInteractionActive = lightEditInProgress,
        lightArtworkResizeActive = artworkResizeActive,
        onLightOrderSettled = { reordered ->
            val safeOrder = restoreHiddenLyric(reordered)
            lightOrder = safeOrder
            onLightOrderEncodedChange(encodeCapsuleLightOrder(safeOrder))
            lightEditInProgress = false
            lightValidationGeneration += 1
        },
        onLightCanvasSettled = { positions, reordered ->
            val safeOrder = restoreHiddenLyric(reordered)
            val visibleSafePositions =
                positions
                    .filterKeys { it in CapsuleLightBaseOrder }
                    .mapValues { (_, value) -> value.coerceAtLeast(0f) }
            val safePositions =
                if (!lyricLineConfigured) {
                    lightCanvasPositions[CapsuleLightBlock.LYRIC]
                        ?.let { lyricTop ->
                            visibleSafePositions +
                                (CapsuleLightBlock.LYRIC to lyricTop.coerceAtLeast(0f))
                        }
                        ?: visibleSafePositions
                } else {
                    visibleSafePositions
                }

            lightOrder = safeOrder
            lightCanvasPositions = safePositions
            onLightOrderEncodedChange(encodeCapsuleLightOrder(safeOrder))
            onLightCanvasPositionsEncodedChange(
                encodeCapsuleLightCanvasPositions(safePositions),
            )

            // v2 is position based. Once it has committed a valid scene, retire the old
            // outer-gap state so it can never influence custom-mode detection again.
            if (lightBlockGaps.values.any { it > 0.01f }) {
                lightBlockGaps = CapsuleLightBaseGaps
                onLightBlockGapsEncodedChange(CapsuleLightBaseGapsEncoded)
            }

            lightEditInProgress = false
            lightValidationGeneration += 1
        },
        onLightGapSettled = { block, gapDp ->
            val safe =
                lightBlockGaps
                    .toMutableMap()
                    .apply { this[block] = gapDp.coerceIn(0f, 1000f) }
                    .toMap()
            lightBlockGaps = safe
            onLightBlockGapsEncodedChange(encodeCapsuleLightBlockGaps(safe))
        },
        onLightGapsSettled = { settled ->
            val safe =
                CapsuleLightBaseOrder.associateWith { block ->
                    (settled[block] ?: 0f).coerceIn(0f, 1000f)
                }
            lightBlockGaps = safe
            onLightBlockGapsEncodedChange(encodeCapsuleLightBlockGaps(safe))
        },
        onLightGapsNormalized = { normalized ->
            val safe =
                CapsuleLightBaseOrder.associateWith { block ->
                    (normalized[block] ?: 0f).coerceIn(0f, 1000f)
                }
            if (safe != lightBlockGaps) {
                lightBlockGaps = safe
                onLightBlockGapsEncodedChange(encodeCapsuleLightBlockGaps(safe))
            }
        },
        lightBlockContent =
            if (!useClayLayout) {
                null
            } else {
                { block, artworkBaseWidth, artworkBaseHeight, artworkMaxHeightScale ->
                    val beginNestedEdit: () -> Unit = {
                        if (block != CapsuleLightBlock.ARTWORK) {
                            artworkSelectionResetToken += 1
                        }
                        lightEditInProgress = true
                        onLightEditSessionActiveChange(true)
                    }

                    when (block) {
                        CapsuleLightBlock.ARTWORK -> {
                            val safeArtworkHeightScale =
                                lightArtworkHeightScale
                                    .coerceIn(
                                        0.55f,
                                        artworkMaxHeightScale.coerceAtLeast(0.55f),
                                    )

                            LaunchedEffect(
                                artworkMaxHeightScale,
                                lightArtworkHeightScale,
                                artworkResizeActive,
                            ) {
                                if (
                                    !artworkResizeActive &&
                                    kotlin.math.abs(
                                        safeArtworkHeightScale - lightArtworkHeightScale,
                                    ) > 0.005f
                                ) {
                                    lightArtworkHeightScale = safeArtworkHeightScale
                                    onArtworkHeightScaleChange(safeArtworkHeightScale)
                                }
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Spacer(Modifier.height(8.dp))
                                CapsuleLightResizableArtwork(
                                    baseWidth = artworkBaseWidth,
                                    baseHeight = artworkBaseHeight,
                                    widthScale = lightArtworkWidthScale,
                                    heightScale = safeArtworkHeightScale,
                                    maxHeightScale = artworkMaxHeightScale,
                                    // Video replaces only the media contents. The saved artwork
                                    // footprint remains in layout so every control below stays
                                    // exactly where the user put it.
                                    editable = lightEditorEnabled && !isCapsuleVideoPlaying,
                                    selectionResetToken = artworkSelectionResetToken,
                                    contentAlignment =
                                        if (isCapsuleVideoPlaying) {
                                            Alignment.TopCenter
                                        } else {
                                            Alignment.Center
                                        },
                                    onEditStarted = {
                                        artworkResizeActive = true
                                        beginNestedEdit()
                                    },
                                    onResizeSettled = { widthScale, heightScale ->
                                        lightArtworkWidthScale = widthScale
                                        lightArtworkHeightScale = heightScale
                                        onArtworkWidthScaleChange(widthScale)
                                        onArtworkHeightScaleChange(heightScale)

                                        artworkResizeActive = false
                                        lightEditInProgress = false
                                        lightValidationGeneration += 1
                                    },
                                ) {
                                    if (isCapsuleVideoPlaying) {
                                        // Fixed legacy video viewport anchored independently from
                                        // the user's artwork shape. Only the media inside ARTWORK
                                        // changes; the rest of the Light layout does not relayout.
                                        Box(
                                            modifier =
                                                Modifier
                                                    .width(artworkBaseWidth)
                                                    .height(artworkBaseHeight),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            artworkContent()
                                        }
                                    } else {
                                        artworkContent()
                                    }
                                }

                            }
                        }

                        CapsuleLightBlock.LYRIC -> {
                            when {
                                lyricLineConfigured -> {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Spacer(Modifier.height(8.dp))
                                        Box(Modifier.width(artworkBaseWidth * lightArtworkWidthScale)) {
                                            CapsuleLightLyricLine(
                                                line =
                                                    if (lyricLineActive) {
                                                        capsuleLightLyricLineAt(
                                                            syncedLyricLines,
                                                            displayPosition + clampOffset(lyricSyncOffsetMs),
                                                        )
                                                    } else {
                                                        null
                                                    },
                                                textColor = textColor,
                                            )
                                        }
                                        Spacer(Modifier.height(10.dp))
                                    }
                                }

                                else -> {
                                    /*
                                     * When the lyric-line feature is disabled, it is absent from
                                     * both the real player and the editor. CanvasV2 still measures
                                     * this optional block as 0 px, so its saved order can survive a
                                     * future re-enable without reserving space or showing a ghost
                                     * editor element in the meantime.
                                     */
                                    Spacer(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(0.dp),
                                    )
                                }
                            }
                        }

                        CapsuleLightBlock.METADATA -> {
                            CapsuleLightReorderRow(
                                order = lightMetadataOrder,
                                editable = lightEditorEnabled,
                                weightFor = { item ->
                                    when (item) {
                                        CapsuleLightMetadataItem.TEXT -> 5f
                                        CapsuleLightMetadataItem.FAVORITE -> 1f
                                    }
                                },
                                onOrderChange = { reordered ->
                                    lightMetadataOrder =
                                        decodeCapsuleLightMetadataOrder(
                                            encodeCapsuleLightMetadataOrder(reordered),
                                        )
                                },
                                onOrderSettled = { reordered ->
                                    val safe =
                                        decodeCapsuleLightMetadataOrder(
                                            encodeCapsuleLightMetadataOrder(reordered),
                                        )
                                    lightMetadataOrder = safe
                                    onLightMetadataOrderEncodedChange(
                                        encodeCapsuleLightMetadataOrder(safe),
                                    )
                                    lightEditInProgress = false
                                    lightValidationGeneration += 1
                                },
                                onEditStarted = beginNestedEdit,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp),
                            ) { item ->
                                when (item) {
                                    CapsuleLightMetadataItem.TEXT -> {
                                        Column(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clipToBounds()
                                                    .padding(end = 10.dp),
                                        ) {
                                            CapsuleTrackTitle(
                                                mediaId = mediaMetadata.id,
                                                title = mediaMetadata.title,
                                                color = textColor,
                                                enabled = !lightEditorEnabled,
                                                modifier = Modifier.fillMaxWidth(),
                                            )

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (mediaMetadata.explicit) {
                                                    ExplicitBadge(color = secondaryText)
                                                    Spacer(Modifier.width(5.dp))
                                                }

                                                Text(
                                                    text = mediaMetadata.artists.joinToString { it.name },
                                                    color = secondaryText,
                                                    fontSize = CapsulePlayerArtistFontSize,
                                                    lineHeight = CapsulePlayerArtistLineHeight,
                                                    fontWeight = FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier =
                                                        Modifier
                                                            .weight(1f, fill = false)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .clickable(
                                                                enabled =
                                                                    navigableArtists.isNotEmpty() &&
                                                                        !lightEditorEnabled,
                                                            ) {
                                                                handleArtistClick()
                                                            }
                                                            .padding(vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }

                                    CapsuleLightMetadataItem.FAVORITE -> {
                                        CapsuleLightFavorite(
                                            liked = liked,
                                            textColor = textColor,
                                            onToggleLike = onToggleLike,
                                            enabled = !lightEditorEnabled,
                                        )
                                    }
                                }
                            }
                        }

                        CapsuleLightBlock.PROGRESS -> {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 7.dp),
                            ) {
                                CapsuleThinSlider(
                                    value = displayPosition.toFloat(),
                                    valueRange = 0f..safeDuration.coerceAtLeast(1L).toFloat(),
                                    enabled = canSeek && safeDuration > 0L && !lightEditorEnabled,
                                    activeColor = textColor.copy(alpha = 0.96f),
                                    inactiveColor = textColor.copy(alpha = 0.24f),
                                    onValueChange = { onSeekPreview(it.toLong()) },
                                    onValueChangeFinished = onSeekFinished,
                                    trackHeight = 2.dp,
                                    thumbRadius = 3.dp,
                                    modifier = Modifier.fillMaxWidth().height(28.dp),
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = makeTimeString(displayPosition),
                                        color = secondaryText,
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        text = if (safeDuration > 0L) "-${makeTimeString(remaining)}" else "",
                                        color = secondaryText,
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }

                        CapsuleLightBlock.MODE_SWITCH -> {
                            CapsuleLightReorderRow(
                                order = lightModeOrder,
                                editable = lightEditorEnabled,
                                weightFor = { item ->
                                    when (item) {
                                        CapsuleLightModeItem.SHUFFLE -> 1f
                                        CapsuleLightModeItem.AUDIO_VIDEO -> 4.5f
                                        CapsuleLightModeItem.SLEEP -> 1f
                                    }
                                },
                                onOrderChange = { reordered ->
                                    lightModeOrder =
                                        decodeCapsuleLightModeOrder(
                                            encodeCapsuleLightModeOrder(reordered),
                                        )
                                },
                                onOrderSettled = { reordered ->
                                    val safe =
                                        decodeCapsuleLightModeOrder(
                                            encodeCapsuleLightModeOrder(reordered),
                                        )
                                    lightModeOrder = safe
                                    onLightModeOrderEncodedChange(
                                        encodeCapsuleLightModeOrder(safe),
                                    )
                                    lightEditInProgress = false
                                    lightValidationGeneration += 1
                                },
                                onEditStarted = beginNestedEdit,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 6.dp),
                            ) { item ->
                                when (item) {
                                    CapsuleLightModeItem.SHUFFLE -> {
                                        androidx.compose.material3.IconButton(
                                            onClick = {
                                                playerConnection.player.shuffleModeEnabled = !shuffleEnabled
                                            },
                                            enabled = !isListenTogetherGuest && !lightEditorEnabled,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.shuffle),
                                                stringResource(R.string.shuffle),
                                                tint =
                                                    textColor.copy(
                                                        alpha = if (shuffleEnabled) 1f else 0.5f,
                                                    ),
                                                modifier = Modifier.size(21.dp),
                                            )
                                        }
                                    }

                                    CapsuleLightModeItem.AUDIO_VIDEO -> {
                                        val edgeInsets =
                                            capsuleLightAvOuterInsets(lightModeOrder)
                                        CapsuleAudioVideoToggle(
                                            lightStyle = true,
                                            state = videoPlaybackState,
                                            textColor = textColor,
                                            enabled = !isListenTogetherGuest && !lightEditorEnabled,
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
                                            lightOrder = lightAvOrder,
                                            lightEditable = lightEditorEnabled,
                                            onLightOrderChange = { reordered ->
                                                lightAvOrder =
                                                    decodeCapsuleLightAvOrder(
                                                        encodeCapsuleLightAvOrder(reordered),
                                                    )
                                            },
                                            onLightOrderSettled = { reordered ->
                                                val safe =
                                                    decodeCapsuleLightAvOrder(
                                                        encodeCapsuleLightAvOrder(reordered),
                                                    )
                                                lightAvOrder = safe
                                                onLightAvOrderEncodedChange(
                                                    encodeCapsuleLightAvOrder(safe),
                                                )
                                                lightEditInProgress = false
                                                lightValidationGeneration += 1
                                            },
                                            onLightEditStarted = beginNestedEdit,
                                        )
                                    }

                                    CapsuleLightModeItem.SLEEP -> {
                                        androidx.compose.material3.IconButton(
                                            onClick = { showSleepTimerDialog = true },
                                            enabled = !isListenTogetherGuest && !lightEditorEnabled,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.bedtime),
                                                stringResource(R.string.sleep_timer),
                                                tint =
                                                    textColor.copy(
                                                        alpha = if (sleepTimerEnabled) 1f else 0.5f,
                                                    ),
                                                modifier = Modifier.size(21.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        CapsuleLightBlock.CONTROLS -> {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = CapsuleLightBlockHorizontalInset,
                                            end = CapsuleLightBlockHorizontalInset,
                                            top = 8.dp,
                                        ),
                            ) {
                                CapsuleLightControls(
                                    textColor = textColor,
                                    shuffleEnabled = shuffleEnabled,
                                    repeatMode = repeatMode,
                                    enabled = !isListenTogetherGuest,
                                    canSkipPrevious = canSkipPrevious,
                                    canSkipNext = canSkipNext,
                                    onShuffle = {
                                        playerConnection.player.shuffleModeEnabled = !shuffleEnabled
                                    },
                                    onPrevious = playerConnection::seekToPrevious,
                                    onNext = playerConnection::seekToNext,
                                    onRepeat = { playerConnection.player.toggleRepeatMode() },
                                    orbit = {
                                        CapsuleOrbitButton(
                                            isPlaying,
                                            isLoading,
                                            visible && motionActive,
                                            textColor,
                                            onPlayPause,
                                            // Transport editing disables the surrounding actions,
                                            // but play/pause remains an emergency action.
                                            enabled = !isListenTogetherGuest,
                                        )
                                    },
                                    interactionEnabled = !lightEditorEnabled,
                                    order = lightTransportOrder,
                                    editable = lightEditorEnabled,
                                    onOrderChange = { reordered ->
                                        lightTransportOrder =
                                            decodeCapsuleLightTransportOrder(
                                                encodeCapsuleLightTransportOrder(reordered),
                                            )
                                    },
                                    onOrderSettled = { reordered ->
                                        val safe =
                                            decodeCapsuleLightTransportOrder(
                                                encodeCapsuleLightTransportOrder(reordered),
                                            )
                                        lightTransportOrder = safe
                                        onLightTransportOrderEncodedChange(
                                            encodeCapsuleLightTransportOrder(safe),
                                        )
                                        lightEditInProgress = false
                                        lightValidationGeneration += 1
                                    },
                                    onEditStarted = beginNestedEdit,
                                )
                            }
                        }
                    }
                }
            },
        artwork = artworkContent,
        details = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (isLight) 24.dp else 22.dp,
                        ),
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .clipToBounds()
                                .padding(
                                    end = 10.dp,
                                ),
                    ) {
                        CapsuleTrackTitle(
                            mediaId = mediaMetadata.id,
                            title = mediaMetadata.title,
                            color =
                                textColor,
                            modifier =
                                Modifier.fillMaxWidth(),
                        )

                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically,
                        ) {
                            if (
                                mediaMetadata.explicit
                            ) {
                                ExplicitBadge(
                                    color =
                                        secondaryText,
                                )

                                Spacer(
                                    Modifier.width(
                                        5.dp,
                                    ),
                                )
                            }

                            Text(
                                text =
                                    mediaMetadata
                                        .artists
                                        .joinToString {
                                            it.name
                                        },
                                color =
                                    secondaryText,
                                fontSize = CapsulePlayerArtistFontSize,
                                lineHeight = CapsulePlayerArtistLineHeight,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow =
                                    TextOverflow.Ellipsis,
                                modifier =
                                    Modifier
                                        .weight(
                                            1f,
                                            fill = false,
                                        )
                                        .clip(
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable(
                                            enabled =
                                                navigableArtists.isNotEmpty(),
                                        ) {
                                            handleArtistClick()
                                        }
                                        .padding(
                                            vertical = 2.dp,
                                        ),
                            )
                        }
                    }

                    CapsuleLightFavorite(liked, textColor, onToggleLike)
                }

                Spacer(
                    Modifier.height(
                        if (isLight) 14.dp else 10.dp,
                    ),
                )

                CapsuleThinSlider(
                    value =
                        displayPosition
                            .toFloat(),
                    valueRange =
                        0f..
                            safeDuration
                                .coerceAtLeast(
                                    1L,
                                )
                                .toFloat(),
                    enabled =
                        canSeek &&
                            safeDuration >
                            0L,
                    activeColor =
                        textColor.copy(
                            alpha = 0.96f,
                        ),
                    inactiveColor =
                        textColor.copy(
                            alpha = 0.24f,
                        ),
                    onValueChange = {
                        onSeekPreview(
                            it.toLong(),
                        )
                    },
                    onValueChangeFinished =
                        onSeekFinished,
                    trackHeight = if (isLight) 2.dp else 6.dp,
                    thumbRadius = if (isLight) 3.dp else 4.dp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(28.dp),
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal =
                                    2.dp,
                            ),
                    horizontalArrangement =
                        Arrangement.SpaceBetween,
                ) {
                    Text(
                        text =
                            makeTimeString(
                                displayPosition,
                            ),
                        color =
                            secondaryText,
                        fontFamily =
                            if (isLight) FontFamily.SansSerif else FontFamily.Monospace,
                        fontSize =
                            if (isLight) 12.sp else 13.sp,
                    )

                    Text(
                        text =
                            if (
                                safeDuration >
                                0L
                            ) {
                                "-${makeTimeString(remaining)}"
                            } else {
                                ""
                            },
                        color =
                            secondaryText,
                        fontFamily =
                            if (isLight) FontFamily.SansSerif else FontFamily.Monospace,
                        fontSize =
                            if (isLight) 12.sp else 13.sp,
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = if (isLight) 6.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (isLight) {
                        androidx.compose.material3.IconButton(
                            onClick = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
                            enabled = !isListenTogetherGuest,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(painterResource(R.drawable.shuffle), stringResource(R.string.shuffle),
                                tint = textColor.copy(alpha = if (shuffleEnabled) 1f else 0.5f),
                                modifier = Modifier.size(21.dp))
                        }
                    }
                    CapsuleAudioVideoToggle(
                        lightStyle = isLight,
                        cosmoStyle = !isLight,
                        state = videoPlaybackState,
                        textColor = textColor,
                        enabled = !isListenTogetherGuest,
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
                            if (isLight) Modifier.weight(1f) else Modifier.width(200.dp),
                    )
    
                    if (isLight) {
                        androidx.compose.material3.IconButton(
                            onClick = { showSleepTimerDialog = true },
                            enabled = !isListenTogetherGuest,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(painterResource(R.drawable.bedtime), stringResource(R.string.sleep_timer),
                                tint = textColor.copy(alpha = if (sleepTimerEnabled) 1f else 0.5f),
                                modifier = Modifier.size(21.dp))
                        }
                    }
                }

                Spacer(
                    Modifier.height(
                        if (isLight) 16.dp else 8.dp,
                    ),
                )

                if (isLight) {
                    CapsuleLightControls(
                        textColor = textColor,
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        enabled = !isListenTogetherGuest,
                        canSkipPrevious = canSkipPrevious,
                        canSkipNext = canSkipNext,
                        onShuffle = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
                        onPrevious = playerConnection::seekToPrevious,
                        onNext = playerConnection::seekToNext,
                        onRepeat = { playerConnection.player.toggleRepeatMode() },
                        orbit = { CapsuleOrbitButton(isPlaying, isLoading, visible && motionActive, textColor, onPlayPause) },
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(
                                    CapsuleControlsShape,
                                )
                                .border(
                                    1.dp,
                                    outline,
                                    CapsuleControlsShape,
                                )
                                .background(
                                    panel,
                                ),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        92.dp,
                                    ),
                            verticalAlignment =
                                Alignment.CenterVertically,
                        ) {
                            CapsuleTransportSideButton(
                                iconRes =
                                    R.drawable.skip_previous,
                                enabled =
                                    canSkipPrevious &&
                                        !isListenTogetherGuest,
                                textColor =
                                    textColor,
                                onClick =
                                    playerConnection::seekToPrevious,
                                modifier =
                                    Modifier.weight(1f),
                            )

                            Box(
                                modifier =
                                    Modifier.weight(
                                        1.18f,
                                    ),
                                contentAlignment =
                                    Alignment.Center,
                            ) {
                                CapsuleOrbitButton(
                                    isPlaying =
                                        isPlaying,
                                    isLoading =
                                        isLoading,
                                    visible = visible && motionActive,
                                    color =
                                        textColor,
                                    onClick = onPlayPause,
                                )
                            }

                            CapsuleTransportSideButton(
                                iconRes =
                                    R.drawable.skip_next,
                                enabled =
                                    canSkipNext &&
                                        !isListenTogetherGuest,
                                textColor =
                                    textColor,
                                onClick =
                                    playerConnection::seekToNext,
                                modifier =
                                    Modifier.weight(1f),
                            )
                        }

                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        1.dp,
                                    )
                                    .background(
                                        outline,
                                    ),
                        )

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        66.dp,
                                    ),
                            verticalAlignment =
                                Alignment.CenterVertically,
                        ) {
                            CapsuleAuxButton(
                                iconRes =
                                    R.drawable.bedtime,
                                tint =
                                    if (
                                        sleepTimerEnabled
                                    ) {
                                        textColor
                                    } else {
                                        textColor.copy(
                                            alpha =
                                                0.82f,
                                        )
                                    },
                                enabled =
                                    !isListenTogetherGuest,
                                onClick = {
                                    showSleepTimerDialog =
                                        true
                                },
                                modifier =
                                    Modifier.weight(1f),
                            )

                            CapsuleAuxButton(
                                iconRes =
                                    when (
                                        repeatMode
                                    ) {
                                        Player.REPEAT_MODE_ONE ->
                                            R.drawable.repeat_one

                                        else ->
                                            R.drawable.repeat
                                    },
                                tint =
                                    if (
                                        repeatMode ==
                                        Player.REPEAT_MODE_OFF ||
                                        isListenTogetherGuest
                                    ) {
                                        textColor.copy(
                                            alpha =
                                                0.46f,
                                        )
                                    } else {
                                        textColor.copy(
                                            alpha =
                                                0.88f,
                                        )
                                    },
                                enabled =
                                    !isListenTogetherGuest,
                                onClick = {
                                    playerConnection.player
                                        .toggleRepeatMode()
                                },
                                modifier =
                                    Modifier.weight(1f),
                            )

                            CapsuleAuxButton(
                                iconRes =
                                    R.drawable.more_horiz,
                                tint =
                                    textColor.copy(
                                        alpha =
                                            0.88f,
                                    ),
                                enabled =
                                    true,
                                onClick =
                                    onMenuClick,
                                modifier =
                                    Modifier.weight(1f),
                            )
                        }
                    }

                }

                Spacer(
                    Modifier.height(
                        14.dp,
                    ),
                )

                Box(
                    modifier =
                        Modifier
                            .align(
                                Alignment.CenterHorizontally,
                            )
                            .width(
                                44.dp,
                            )
                            .height(
                                4.dp,
                            )
                            .clip(
                                CircleShape,
                            )
                            .background(
                                textColor.copy(
                                    alpha =
                                        0.22f,
                                ),
                            )
                            .clickable(
                                onClick =
                                    onExpandQueue,
                            ),
                )

                Spacer(
                    Modifier.height(
                        12.dp,
                    ),
                )
            }
        },
    )
}


@Composable
internal fun CapsuleThinSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
    thumbRadius: Dp = 4.dp,
) {
    val rangeSize =
        (
            valueRange.endInclusive -
                valueRange.start
        ).coerceAtLeast(0.0001f)

    val fraction =
        (
            (value - valueRange.start) /
                rangeSize
        ).coerceIn(0f, 1f)

    Canvas(
        modifier =
            modifier
                .pointerInput(
                    enabled,
                    valueRange.start,
                    valueRange.endInclusive,
                ) {
                    if (!enabled) {
                        return@pointerInput
                    }

                    val widthPx =
                        size.width
                            .toFloat()
                            .coerceAtLeast(1f)

                    val insetPx =
                        thumbRadius.toPx()

                    val usableWidth =
                        (
                            widthPx -
                                insetPx * 2f
                        ).coerceAtLeast(1f)

                    fun updateFromX(
                        x: Float,
                    ) {
                        val newFraction =
                            (
                                (x - insetPx) /
                                    usableWidth
                            ).coerceIn(0f, 1f)

                        onValueChange(
                            valueRange.start +
                                rangeSize *
                                newFraction,
                        )
                    }

                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed =
                                    false,
                            )

                        updateFromX(
                            down.position.x,
                        )

                        down.consume()

                        while (true) {
                            val event =
                                awaitPointerEvent()

                            val change =
                                event.changes
                                    .firstOrNull {
                                        it.id ==
                                            down.id
                                    }
                                    ?: break

                            if (!change.pressed) {
                                onValueChangeFinished()
                                break
                            }

                            updateFromX(
                                change.position.x,
                            )

                            change.consume()
                        }
                    }
                },
    ) {
        val centerY =
            size.height / 2f

        val radiusPx =
            thumbRadius.toPx()

        val startX =
            radiusPx

        val endX =
            (
                size.width -
                    radiusPx
            ).coerceAtLeast(startX)

        val activeEnd =
            startX +
                (
                    endX -
                        startX
                ) * fraction

        val resolvedActive =
            if (enabled) {
                activeColor
            } else {
                activeColor.copy(
                    alpha =
                        activeColor.alpha *
                            0.38f,
                )
            }

        val resolvedInactive =
            if (enabled) {
                inactiveColor
            } else {
                inactiveColor.copy(
                    alpha =
                        inactiveColor.alpha *
                            0.45f,
                )
            }

        drawLine(
            color =
                resolvedInactive,
            start =
                Offset(
                    startX,
                    centerY,
                ),
            end =
                Offset(
                    endX,
                    centerY,
                ),
            strokeWidth =
                trackHeight.toPx(),
            cap =
                StrokeCap.Round,
        )

        if (activeEnd > startX) {
            drawLine(
                color =
                    resolvedActive,
                start =
                    Offset(
                        startX,
                        centerY,
                    ),
                end =
                    Offset(
                        activeEnd,
                        centerY,
                    ),
                strokeWidth =
                    trackHeight.toPx(),
                cap =
                    StrokeCap.Round,
            )
        }

        drawCircle(
            color =
                resolvedActive,
            radius =
                radiusPx,
            center =
                Offset(
                    activeEnd,
                    centerY,
                ),
        )
    }
}

@Composable
private fun CapsuleTransportSideButton(
    iconRes: Int,
    enabled: Boolean,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(
                    92.dp,
                )
                .clickable(
                    enabled =
                        enabled,
                    onClick =
                        onClick,
                ),
        contentAlignment =
            Alignment.Center,
    ) {
        Icon(
            painter =
                painterResource(
                    iconRes,
                ),
            contentDescription =
                null,
            tint =
                textColor.copy(
                    alpha =
                        if (enabled) {
                            0.92f
                        } else {
                            0.25f
                        },
                ),
            modifier =
                Modifier.size(
                    35.dp,
                ),
        )
    }
}

@Composable
private fun CapsuleAuxButton(
    iconRes: Int,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(
                    66.dp,
                )
                .clickable(
                    enabled =
                        enabled,
                    onClick =
                        onClick,
                ),
        contentAlignment =
            Alignment.Center,
    ) {
        Icon(
            painter =
                painterResource(
                    iconRes,
                ),
            contentDescription =
                null,
            tint =
                if (enabled) {
                    tint
                } else {
                    tint.copy(
                        alpha =
                            0.38f,
                    )
                },
            modifier =
                Modifier.size(
                    25.dp,
                ),
        )
    }
}

/**
 * Whether the comet is allowed to turn.
 *
 * Named and separated because the visibility term is the whole point and is easy to lose: without
 * it the clock runs for as long as anything is playing, on a button behind a collapsed sheet or
 * behind a backgrounded app, requesting a frame every vsync the entire time.
 */
internal fun orbitShouldTurn(
    isPlaying: Boolean,
    isLoading: Boolean,
    visible: Boolean,
): Boolean = isPlaying && !isLoading && visible

/**
 * Donor Capsule comet button.
 *
 * Animatable deliberately survives Play/Pause changes.
 * Pause freezes the dot at the exact current angle;
 * Resume continues from the same angle.
 */
@Composable
internal fun CapsuleOrbitButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    visible: Boolean,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val rotation =
        rememberCapsuleCometRotation(
            isPlaying = isPlaying,
            isLoading = isLoading,
            visible = visible,
        )

    val alpha by
        animateFloatAsState(
            targetValue =
                if (isPlaying) {
                    1f
                } else {
                    0.46f
                },
            animationSpec =
                tween(
                    durationMillis =
                        300,
                ),
            label =
                "CapsuleOrbitPauseAlpha",
        )

    Box(
        modifier =
            Modifier
                .size(
                    82.dp,
                )
                .clip(
                    CircleShape,
                )
                // No paused-state disc: the orbit is the play/pause button in
                // Super, Light and Immersive, so the three designs stay aligned.
                .clickable(
                    enabled = enabled,
                    onClick =
                        onClick,
                ),
        contentAlignment =
            Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color =
                    color.copy(
                        alpha =
                            0.82f,
                    ),
                strokeWidth =
                    2.dp,
                modifier =
                    Modifier.size(
                        32.dp,
                    ),
            )
        } else {
            Canvas(
                Modifier.size(
                    68.dp,
                ),
            ) {
                val w =
                    size.width

                val h =
                    size.height

                val cx =
                    w / 2f

                val cy =
                    h / 2f

                val rx =
                    w * 0.47f

                val ry =
                    h * 0.19f

                val orbitColor =
                    color.copy(
                        alpha =
                            alpha,
                    )

                rotate(
                    -25f,
                ) {
                    drawOval(
                        color =
                            orbitColor,
                        topLeft =
                            Offset(
                                cx - rx,
                                cy - ry,
                            ),
                        size =
                            Size(
                                rx * 2f,
                                ry * 2f,
                            ),
                        style =
                            Stroke(
                                width =
                                    w *
                                        0.045f,
                            ),
                    )

                    val angle =
                        Math.toRadians(
                            rotation.value
                                .toDouble(),
                        )

                    val previousAngle =
                        Math.toRadians(
                            (
                                rotation.value -
                                    15f
                            ).toDouble(),
                        )

                    val point =
                        Offset(
                            x =
                                cx +
                                    rx *
                                    cos(angle)
                                        .toFloat(),
                            y =
                                cy +
                                    ry *
                                    sin(angle)
                                        .toFloat(),
                        )

                    val tail =
                        Offset(
                            x =
                                cx +
                                    rx *
                                    cos(
                                        previousAngle,
                                    ).toFloat(),
                            y =
                                cy +
                                    ry *
                                    sin(
                                        previousAngle,
                                    ).toFloat(),
                        )

                    drawLine(
                        color =
                            orbitColor.copy(
                                alpha =
                                    alpha *
                                        0.35f,
                            ),
                        start =
                            tail,
                        end =
                            point,
                        strokeWidth =
                            w *
                                0.035f,
                    )

                    drawCircle(
                        color =
                            orbitColor,
                        radius =
                            w *
                                0.052f,
                        center =
                            point,
                    )
                }

                drawCircle(
                    color =
                        orbitColor,
                    radius =
                        w *
                            0.115f,
                    center =
                        Offset(
                            cx,
                            cy,
                        ),
                )
            }
        }
    }
}
