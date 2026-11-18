
/* * Velune / Capsule MUSIC
 * Capsule Lyrics visual layer
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.constants.CapsulePlayerDesign
import com.nikhil.yt.constants.CapsulePlayerDesignKey
import com.nikhil.yt.constants.LyricsUsePlayerThemeKey
import com.nikhil.yt.constants.ReleaseAppearanceDefaults
import com.nikhil.yt.constants.PlayerBackgroundStyle
import com.nikhil.yt.constants.PlayerBackgroundStyleKey
import com.nikhil.yt.R
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.component.Lyrics
import com.nikhil.yt.ui.motion.CapsuleStandardEasing
import com.nikhil.yt.ui.theme.PlayerBackgroundColorUtils
import com.nikhil.yt.utils.makeTimeString
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

private val CapsuleLyricsBackground =
    Color(0xFF141414)

private val CapsuleLyricsText =
    Color(0xFFF0F0F0)

private val CapsuleLyricsSecondary =
    Color(0xFF858585)

private val lyricsPanelColorShape =
    RoundedCornerShape(24.dp)

/**
 * The heavy Lyrics list keeps its natural layout, fonts and hit targets.
 * Only this small RenderNode is animated; no per-frame composition/layout,
 * no blur/shader work, no independent coroutine per child at 60 Hz.
 */
private fun Modifier.lyricsInkLayer(
    motionPosition: () -> Float,
    zone: LyricsInkZone,
    density: Float,
): Modifier = graphicsLayer {
    val position = motionPosition()
    alpha = capsuleLyricsZoneOpacity(position, zone)
    translationY = capsuleLyricsZoneFloatDp(position, zone) * density
}


@Composable
internal fun CapsuleLyricsBackdropLayer(
    mediaMetadata: MediaMetadata,
    immersiveArtworkTone: ImmersiveArtworkTone? = null,
    playerArtworkColors: List<Color> = emptyList(),
    backdropAnimationTime: State<Long>? = null,
    isVisible: Boolean = true,
    instantBackdrop: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val onScreen = appIsOnScreen()
    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()

    val lyricsUsePlayerTheme by
        rememberPreference(
            LyricsUsePlayerThemeKey,
            defaultValue = ReleaseAppearanceDefaults.usePlayerThemeInLyrics,
        )
    val playerBackground by
        rememberEnumPreference(
            PlayerBackgroundStyleKey,
            defaultValue = ReleaseAppearanceDefaults.playerBackground,
        )
    val playerDesign by
        rememberEnumPreference(
            CapsulePlayerDesignKey,
            defaultValue = ReleaseAppearanceDefaults.playerDesign,
        )

    val lyricsBackdrop =
        capsuleLyricsBackdrop(
            usePlayerTheme = lyricsUsePlayerTheme,
            playerDesign = playerDesign,
            playerBackground = playerBackground,
        )
    // Immersion uses its cropped lower-edge sample, never the whole-cover palette.
    val needsArtworkColors =
        lyricsBackdrop is CapsuleLyricsBackdrop.PlayerTheme &&
            lyricsBackdrop.style != PlayerBackgroundStyle.DEFAULT
    val localArtworkScene =
        rememberCapsuleArtworkScene(
            mediaMetadata = mediaMetadata,
            // Lyrics must request its own CURRENT track, even if the parent supplies
            // three placeholder RGB values. Those placeholders used to suppress the
            // fetch permanently after many rapid skips.
            enabled = onScreen && needsArtworkColors,
            animatePalette = false,
            retainWhenDisabled = true,
        )
    // Keep ONE scene across media IDs, not one palette per track. A fresh track's
    // neutral pending palette is not decoded artwork and must not replace a warm
    // scene in one frame (the reported bright -> grey "light switch").
    var lastUsablePalette by remember { mutableStateOf<List<Color>>(emptyList()) }
    var lastUsableField by remember { mutableStateOf<CapsuleColorField?>(null) }
    // The parent palette is frequently the synthetic neutral scene while a thumbnail
    // is being loaded. Let the shared cache/local decoder certify the CURRENT mediaId;
    // taking an apparently nonempty parent RGB list previously suppressed extraction.
    val currentPalette = localArtworkScene.colors
        .takeIf { localArtworkScene.ready && it.size >= 3 }
        .orEmpty()
    LaunchedEffect(mediaMetadata.id, currentPalette, localArtworkScene.field) {
        if (currentPalette.isNotEmpty()) {
            lastUsablePalette = currentPalette
            lastUsableField = localArtworkScene.field
        } else {
            // Bounded retry may still be processing. Keep the outgoing palette and
            // spatial field alive, then let both settle to neutral if no art exists.
            kotlinx.coroutines.delay(2600L)
            lastUsablePalette = List(3) { Color(0xFF1A1B21) }
            lastUsableField = null
        }
    }
    val paletteTarget = currentPalette.ifEmpty { lastUsablePalette }
        .ifEmpty { List(3) { Color(0xFF1A1B21) } }
    val artworkColors = paletteTarget.mapIndexed { index, target ->
        animateColorAsState(
            targetValue = target,
            animationSpec = tween(1150, easing = CapsuleStandardEasing),
            label = "lyricsSharedTrackColor$index",
        ).value
    }
    // Keep the spatial field paired with a decoded current image. An unsuccessful
    // source must not erase the previous field in the middle of a transition.



    BoxWithConstraints(modifier = modifier.fillMaxSize().background(CapsuleLyricsBackground)) {
        val artworkAspectRatio = maxWidth.value /
            immersiveArtworkHeight(maxHeight).value.coerceAtLeast(1f)
        // Crossfade only changes of layout/style. Track palettes keep the same renderer and
        // the same 1400ms interpolation as the player; no permanent second backdrop layer.
        // When the Lyrics aperture itself is travelling, never blend
        // two FULL-SCREEN backdrops through an offscreen alpha layer.
        // That composited pale backgrounds over dark ones as the reported
        // translucent white "fog". Style changes when NOT in a transition
        // keep their original crossfade.
        val renderBackdrop: @Composable (CapsuleLyricsBackdrop) -> Unit = { backdrop ->
            val base = if (
                backdrop is CapsuleLyricsBackdrop.PlayerTheme &&
                backdrop.style == PlayerBackgroundStyle.DEFAULT
            ) MaterialTheme.colorScheme.background else CapsuleLyricsBackground
            Box(Modifier.fillMaxSize().background(base)) {
                when (backdrop) {
                    CapsuleLyricsBackdrop.Solid -> Unit
                    CapsuleLyricsBackdrop.ImmersiveColoring -> {
                        // Standalone Lyrics uses the same crop and sampler as the player host.
                        val tone = immersiveArtworkTone ?: rememberImmersiveEdgeColor(
                            mediaMetadata = mediaMetadata,
                            enabled = onScreen && isVisible &&
                                lyricsBackdrop == CapsuleLyricsBackdrop.ImmersiveColoring,
                            visibleArtworkAspectRatio = artworkAspectRatio,
                        )
                        var retainedToneEdge by remember { mutableStateOf(IMMERSIVE_NEUTRAL_COLOR) }
                        LaunchedEffect(mediaMetadata.id, mediaMetadata.thumbnailUrl, tone.ready, tone.edge) {
                            if (tone.ready) {
                                retainedToneEdge = tone.edge
                            } else {
                                // The previous hue remains painted while the next track is sampled;
                                // grey enters only after a bounded failed/missing-cover interval.
                                kotlinx.coroutines.delay(2600L)
                                retainedToneEdge = IMMERSIVE_NEUTRAL_COLOR
                            }
                        }
                        ImmersiveLyricsColoring(if (tone.ready) tone.edge else retainedToneEdge)
                    }
                    is CapsuleLyricsBackdrop.PlayerTheme -> PlayerBackground(
                        playerBackground = backdrop.style,
                        gradientColors = artworkColors,
                        artworkField = if (localArtworkScene.ready) localArtworkScene.field else lastUsableField,
                        animated = isVisible && onScreen && isPlaying &&
                            playbackState == Player.STATE_READY && backdrop == lyricsBackdrop,
                        sharedAnimationTime = backdropAnimationTime,
                    )
                }
            }
        }
        if (instantBackdrop) {
            renderBackdrop(lyricsBackdrop)
        } else {
            Crossfade(
                targetState = lyricsBackdrop,
                animationSpec = tween(630, easing = CapsuleStandardEasing),
                label = "lyricsBackdropStyle",
            ) { backdrop -> renderBackdrop(backdrop) }
        }
    }
}

@Composable
private fun ImmersiveLyricsColoring(edge: Color) {
    val stops = remember(edge) {
        PlayerBackgroundColorUtils.buildImmersiveLyricsColoringStops(edge)
    }
    // Build the colour policy once per cover, not once per animation frame. Read the animated
    // colours in drawing so a background fade never recomposes the lyrics or player controls.
    val colors = stops.mapIndexed { index, stop ->
        animateColorAsState(
            targetValue = stop.second,
            animationSpec = tween(1400, easing = CapsuleStandardEasing),
            label = "immersiveLyricsColor$index",
        )
    }
    Box(Modifier.fillMaxSize().drawWithCache {
        val brush = Brush.verticalGradient(
            colorStops = stops.mapIndexed { index, stop -> stop.first to colors[index].value }
                .toTypedArray(),
        )
        onDrawBehind { drawRect(brush) }
    })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CapsuleLyricsContent(
    mediaMetadata: MediaMetadata,
    sliderPosition: Long?,
    positionMs: Long,
    durationMs: Long,
    onClose: () -> Unit,
    onMenuClick: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onLineSeek: (Long) -> Unit,
    playerArtworkColors: List<Color> = emptyList(),
    backdropAnimationTime: State<Long>? = null,
    drawBackdrop: Boolean = true,
    isVisible: Boolean = true,
    motionPosition: () -> Float = { 0f },
    modifier: Modifier = Modifier,
) {
    val playerConnection =
        LocalPlayerConnection.current ?: return

    val player =
        playerConnection.player

    val onScreen = appIsOnScreen()
    val visualsActive = isVisible && onScreen
    val motionDensity = LocalDensity.current.density

    if (!onScreen) {
        // Background means genuinely invisible. Tear down the expensive lyrics subtree instead of
        // merely stopping its clocks: no player-flow collectors, sliders, text layout or shadows
        // stay subscribed while Android is showing another app. Refocus on return is immediate.
        Box(modifier = modifier.fillMaxSize())
        return
    }

    val playbackState by
        playerConnection.playbackState.collectAsStateWithLifecycle()

    val isPlaying by
        playerConnection.isPlaying.collectAsStateWithLifecycle()

    // Same treatment as the full player: the control card is a translucent layer, not an
    // opaque black block, so the selected lyrics background colours the panel underneath it.
    val menuInteraction = remember { MutableInteractionSource() }
    val menuPressed by menuInteraction.collectIsPressedAsState()
    val menuScale by animateFloatAsState(
        targetValue = if (menuPressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 145),
        label = "capsuleLyricsMenuPress",
    )
    val lyricsPanelColor = CapsuleLyricsText.copy(alpha = 0.025f)
    val lyricsOutlineColor = CapsuleLyricsText.copy(alpha = 0.16f)

    val canSkipPrevious by
        playerConnection.canSkipPrevious.collectAsStateWithLifecycle()

    val canSkipNext by
        playerConnection.canSkipNext.collectAsStateWithLifecycle()

    val volumeState =
        playerConnection
            .service
            .playerVolume
            .collectAsStateWithLifecycle()

    val isLoading = playbackState == Player.STATE_BUFFERING

    /*
     * LyricsScreen is created only when the transition begins. Its own
     * position/duration state used to start at 0 / TIME_UNSET for one frame,
     * which made the progress line flash. Use the live ExoPlayer values as
     * the first-frame fallback so the line appears at the correct position
     * immediately.
     */
    val liveDuration =
        player.duration

    val durationInputValid =
        durationMs > 0L &&
            durationMs != C.TIME_UNSET

    val safeDuration =
        when {
            durationInputValid ->
                durationMs

            liveDuration > 0L &&
                liveDuration != C.TIME_UNSET ->
                liveDuration

            else ->
                0L
        }

    val displayPosition =
        (
            sliderPosition
                ?: if (durationInputValid) {
                    positionMs
                } else {
                    player.currentPosition
                }
        ).coerceAtLeast(0L)

    val remaining =
        (
            safeDuration -
                displayPosition
        ).coerceAtLeast(0L)

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        if (drawBackdrop) {
            CapsuleLyricsBackdropLayer(
                mediaMetadata = mediaMetadata,
                playerArtworkColors = playerArtworkColors,
                backdropAnimationTime = backdropAnimationTime,
                isVisible = isVisible,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                    WindowInsets.systemBarsIgnoringVisibility.only(
                        WindowInsetsSides.Top +
                            WindowInsetsSides.Horizontal +
                            WindowInsetsSides.Bottom,
                    ),
                )
                .padding(
                    horizontal = 18.dp,
                    vertical = 8.dp,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .lyricsInkLayer(motionPosition, LyricsInkZone.HEADER, motionDensity),
        ) {
            Box(
                modifier =
                    Modifier
                        .align(
                            Alignment.CenterStart,
                        )
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(
                            onClick = onClose,
                        ),
                contentAlignment =
                    Alignment.Center,
            ) {
                Icon(
                    painter =
                        painterResource(
                            R.drawable.expand_more,
                        ),
                    contentDescription = null,
                    tint =
                        CapsuleLyricsText,
                    modifier =
                        Modifier.size(30.dp),
                )
            }

            Column(
                modifier =
                    Modifier
                        .align(
                            Alignment.Center,
                        )
                        .padding(
                            horizontal = 60.dp,
                        ),
                horizontalAlignment =
                    Alignment.CenterHorizontally,
            ) {
                Text(
                    text =
                        mediaMetadata.title,
                    color =
                        CapsuleLyricsText,
                    fontSize = 19.sp,
                    lineHeight = 22.sp,
                    fontWeight =
                        FontWeight.SemiBold,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis,
                )

                Text(
                    text =
                        mediaMetadata
                            .artists
                            .joinToString {
                                it.name
                            },
                    color =
                        CapsuleLyricsSecondary,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontFamily =
                        FontFamily.Monospace,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier =
                    Modifier
                        .align(
                            Alignment.CenterEnd,
                        )
                        .size(48.dp)
                        .clip(CircleShape)
                        .graphicsLayer {
                            scaleX = menuScale
                            scaleY = menuScale
                        }
                        .clickable(
                            interactionSource = menuInteraction,
                            indication = null,
                            onClick = onMenuClick,
                        ),
                contentAlignment =
                    Alignment.Center,
            ) {
                Icon(
                    painter =
                        painterResource(
                            R.drawable.capsule_transport_more,
                        ),
                    contentDescription = stringResource(R.string.more),
                    tint =
                        CapsuleLyricsText,
                    modifier =
                        Modifier.size(28.dp),
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        top = 6.dp,
                        bottom = 8.dp,
                    )
                    .lyricsInkLayer(motionPosition, LyricsInkZone.LINES, motionDensity),
        ) {
            Lyrics(
                sliderPositionProvider = {
                    sliderPosition
                },
                isVisible = isVisible,
                onLineSeek = onLineSeek,
            )
        }

        CapsuleThinSlider(
            value =
                displayPosition
                    .toFloat(),
            valueRange =
                0f..
                    safeDuration
                        .coerceAtLeast(1L)
                        .toFloat(),
            enabled =
                safeDuration > 0L,
            activeColor =
                CapsuleLyricsText.copy(alpha = 0.96f),
            inactiveColor =
                CapsuleLyricsText.copy(
                    alpha = 0.24f,
                ),
            onValueChange = {
                onSeekPreview(
                    it.toLong(),
                )
            },
            onValueChangeFinished =
                onSeekFinished,
            smoothPlayback = visualsActive && isPlaying && sliderPosition == null,
            trackHeight =
                8.dp,
            thumbRadius =
                0.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .lyricsInkLayer(motionPosition, LyricsInkZone.TIMELINE, motionDensity),
        )

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .lyricsInkLayer(motionPosition, LyricsInkZone.TIMELINE, motionDensity),
            horizontalArrangement =
                Arrangement.SpaceBetween,
        ) {
            Text(
                text =
                    makeTimeString(
                        displayPosition,
                    ),
                color =
                    CapsuleLyricsSecondary,
                fontFamily =
                    FontFamily.Monospace,
                fontSize = 13.sp,
            )

            Text(
                text =
                    if (safeDuration > 0L) {
                        "-${
                            makeTimeString(
                                remaining,
                            )
                        }"
                    } else {
                        ""
                    },
                color =
                    CapsuleLyricsSecondary,
                fontFamily =
                    FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }

        Spacer(
            Modifier.height(12.dp),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(
                        lyricsPanelColorShape,
                    )
                    .border(
                        width = 1.dp,
                        color =
                            lyricsOutlineColor,
                        shape =
                            lyricsPanelColorShape,
                    )
                    .background(
                        lyricsPanelColor,
                    )
                    .lyricsInkLayer(motionPosition, LyricsInkZone.CONTROLS, motionDensity),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(86.dp),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                CapsuleLyricsSideButton(
                    iconRes =
                        R.drawable.capsule_transport_previous,
                    label = stringResource(androidx.media3.ui.R.string.exo_controls_previous_description),
                    enabled =
                        canSkipPrevious,
                    onClick =
                        playerConnection::seekToPrevious,
                    modifier =
                        Modifier.weight(1f),
                )

                Box(
                    modifier =
                        Modifier.weight(1.2f),
                    contentAlignment =
                        Alignment.Center,
                ) {
                    CapsuleOrbitButton(
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        visible = visualsActive,
                        color = CapsuleLyricsText,
                        onClick = {
                            if (
                                playbackState ==
                                Player.STATE_ENDED
                            ) {
                                playerConnection
                                    .player
                                    .seekTo(
                                        0,
                                        0,
                                    )

                                playerConnection
                                    .player
                                    .playWhenReady =
                                    true
                            } else {
                                playerConnection
                                    .player
                                    .togglePlayPause()
                            }
                        },
                    )
                }

                CapsuleLyricsSideButton(
                    iconRes =
                        R.drawable.capsule_transport_next,
                    label = stringResource(androidx.media3.ui.R.string.exo_controls_next_description),
                    enabled =
                        canSkipNext,
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
                        .height(1.dp)
                        .background(
                            lyricsOutlineColor,
                        ),
            )

            // One matte volume capsule: seven equally rounded cuts, no independent thumb.
            Row(
                modifier = Modifier.fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsuleLyricsVolumeIcon(
                    muted = true,
                    onClick = { playerConnection.service.playerVolume.value = 0f },
                )
                CapsuleLyricsVolumeSlider(
                    value = volumeState.value,
                    onValueChange = { playerConnection.service.playerVolume.value = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                CapsuleLyricsVolumeIcon(
                    muted = false,
                    onClick = { playerConnection.service.playerVolume.value = 1f },
                )
            }
        }

        Spacer(
            Modifier.height(10.dp),
        )
        }
    }
}


/*
 * CapsuleThinSlider used to be copied here verbatim — two hundred identical lines in two files
 * in the same package. Sharing the player's copy removed the duplicate rather than adding a
 * third for the immersive screen.
 */

@Composable
private fun CapsuleLyricsSideButton(
    iconRes: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = tween(durationMillis = 145),
        label = "capsuleLyricsSkipPress",
    )
    Box(
        modifier = modifier
            .height(86.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = CapsuleLyricsText.copy(alpha = if (enabled) 0.92f else 0.25f),
            modifier = Modifier.size(29.dp),
        )
    }
}
