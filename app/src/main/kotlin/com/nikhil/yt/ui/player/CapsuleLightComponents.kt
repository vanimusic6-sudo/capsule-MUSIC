package com.nikhil.yt.ui.player

import com.nikhil.yt.ui.component.CapsuleFavoriteIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.nikhil.yt.ui.component.CapsuleFavoriteColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsulePlayerDesign

private val LocalCapsuleLightMenu = staticCompositionLocalOf<() -> Unit> { {} }

/** Soft corners for Capsule Light's low-contrast controls. */
internal val CapsuleLightPanelRadius = 18.dp
internal val CapsuleLightPanelShape = RoundedCornerShape(CapsuleLightPanelRadius)

/** The AUDIO/VIDEO switch shares the transport panel's shell, so it shares its geometry. */
internal val CapsuleLightToggleHeight = 48.dp
internal val CapsuleLightToggleInset = 4.dp

/**
 * Fixed system zone at the bottom of Light. It belongs to queue navigation, not to the user's
 * editable canvas, so clay elements may never occupy or cross it.
 */
internal val CapsuleLightQueueDockHeight = 30.dp

/** Both designs host the same artwork, metadata and playback actions. */
@Composable
internal fun CapsulePlayerLayout(
    design: CapsulePlayerDesign,
    textColor: Color,
    onCollapse: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    onExpandQueue: () -> Unit = {},
    lightEditorEnabled: Boolean = false,
    lightOrder: List<CapsuleLightBlock> = CapsuleLightBaseOrder,
    lightCanvasPositionsDp: Map<CapsuleLightBlock, Float> = emptyMap(),
    lightGapsDp: Map<CapsuleLightBlock, Float> = CapsuleLightBaseGaps,
    onLightOrderChange: (List<CapsuleLightBlock>) -> Unit = {},
    onLightOrderSettled: (List<CapsuleLightBlock>) -> Unit = {},
    onLightCanvasSettled: (
        Map<CapsuleLightBlock, Float>,
        List<CapsuleLightBlock>,
    ) -> Unit = { _, _ -> },
    onLightGapSettled: (CapsuleLightBlock, Float) -> Unit = { _, _ -> },
    onLightGapsSettled: (Map<CapsuleLightBlock, Float>) -> Unit = {},
    onLightGapsNormalized: (Map<CapsuleLightBlock, Float>) -> Unit = {},
    onLightEditStarted: () -> Unit = {},
    lightInteractionActive: Boolean = false,
    /**
     * Optional block renderer used by the first Capsule "clay" editor.
     *
     * When supplied, Light is rendered as reorderable slots instead of the fixed artwork/details
     * stack. Dense/Immersive and old call sites keep the exact legacy path below.
     */
    lightBlockContent: (@Composable (CapsuleLightBlock, Dp, Float) -> Unit)? = null,
    /**
     * The sounding lyric line, or null when it is switched off.
     *
     * Null rather than an empty lambda because the row's gaps go with it: a layout that reserved
     * the space either way would keep the controls pushed down for a feature that is not there.
     */
    lyricLine: (@Composable () -> Unit)? = null,
    artwork: @Composable () -> Unit,
    details: @Composable () -> Unit,
) {
    val light = design == CapsulePlayerDesign.LIGHT
    if (light) {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
            // The details column also carries the sounding lyric line above it, when it is on.
            val lyricSpace = if (lyricLine != null) CapsuleLightLyricLineHeight else 0.dp
            val detailsSpace = (320.dp + lyricSpace) * fontScale
            val customCanvasHeight =
                if (lightBlockContent != null) {
                    (maxHeight - CapsuleLightQueueDockHeight).coerceAtLeast(0.dp)
                } else {
                    maxHeight
                }
            val artworkVerticalBudget =
                (customCanvasHeight - detailsSpace)
                    .coerceAtLeast(120.dp)

            // The editable Light must fit inside a real fixed canvas. On short screens the card is
            // allowed to start smaller than legacy Light's old 160dp floor rather than forcing the
            // whole player to become scrollable.
            val artworkSide = minOf(
                (maxWidth - 48.dp).coerceAtLeast(120.dp),
                if (lightBlockContent != null) {
                    artworkVerticalBudget.coerceIn(120.dp, 360.dp)
                } else {
                    (maxHeight - detailsSpace).coerceIn(160.dp, 360.dp)
                },
            )

            val artworkHeightBudgetAfterGaps =
                (customCanvasHeight - detailsSpace)
                    .coerceAtLeast(artworkSide * 0.55f)
            val artworkMaxHeightScale =
                if (artworkSide.value > 0f) {
                    (artworkHeightBudgetAfterGaps.value / artworkSide.value)
                        .coerceIn(0.55f, 1.35f)
                } else {
                    1f
                }
            val scrollState = rememberScrollState()
            val openQueue by rememberUpdatedState(onExpandQueue)
            val queueThreshold = with(LocalDensity.current) { 64.dp.toPx() }
            // A downward pull past the top keeps Capsule's existing queue gesture.
            // Scrolling back through the controls must not open the queue.
            val queueScroll = remember(scrollState, queueThreshold, lightEditorEnabled) {
                object : NestedScrollConnection {
                    var pulled = 0f
                    var opened = false

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        // Editing owns every drag gesture, including artwork resize handles.
                        // The queue pull must be completely dormant or it steals downward resize.
                        if (lightEditorEnabled) {
                            pulled = 0f
                            opened = false
                            return Offset.Zero
                        }
                        if (source != NestedScrollSource.UserInput) return Offset.Zero
                        if (available.y > 0f && scrollState.value == 0) {
                            pulled += available.y
                            if (!opened && pulled >= queueThreshold) {
                                opened = true
                                openQueue()
                            }
                            return Offset(0f, available.y)
                        }
                        if (consumed.y != 0f || available.y < 0f) pulled = 0f
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (lightEditorEnabled) {
                            pulled = 0f
                            opened = false
                            return Velocity.Zero
                        }
                        val wasPulling = pulled > 0f || opened
                        pulled = 0f
                        opened = false
                        return if (wasPulling) Velocity(0f, available.y) else Velocity.Zero
                    }
                }
            }
            if (lightBlockContent != null) {
                val editableHeight =
                    (maxHeight - CapsuleLightQueueDockHeight)
                        .coerceAtLeast(0.dp)

                CompositionLocalProvider(LocalCapsuleLightMenu provides onMenuClick) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(maxHeight)
                                .clipToBounds(),
                    ) {
                        CapsuleLightCanvasV2(
                            order = lightOrder,
                            positionsDp = lightCanvasPositionsDp,
                            editable = lightEditorEnabled,
                            viewportHeight = editableHeight,
                            onLayoutSettled = onLightCanvasSettled,
                            onEditStarted = onLightEditStarted,
                            externalGestureActive = lightInteractionActive,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(editableHeight)
                                    .clipToBounds(),
                        ) { block, canvasMaxArtworkHeight ->
                            val effectiveArtworkMaxScale =
                                if (
                                    block == CapsuleLightBlock.ARTWORK &&
                                    canvasMaxArtworkHeight != null &&
                                    artworkSide.value > 0f
                                ) {
                                    // The v2 canvas measures every real block. Its budget is the
                                    // source of truth; the old 320dp details estimate can otherwise
                                    // reject growth even when the actual scene has free room.
                                    val artworkFrameBudget =
                                        (canvasMaxArtworkHeight - 8.dp)
                                            .coerceAtLeast(artworkSide * 0.55f)
                                    (
                                        artworkFrameBudget.value /
                                            artworkSide.value
                                        )
                                        .coerceIn(0.55f, 1.35f)
                                } else {
                                    artworkMaxHeightScale
                                }

                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                lightBlockContent(
                                    block,
                                    artworkSide,
                                    effectiveArtworkMaxScale,
                                )
                            }
                        }

                        CapsuleLightQueueDock(
                            textColor = textColor,
                            enabled = !lightEditorEnabled,
                            onExpandQueue = onExpandQueue,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(CapsuleLightQueueDockHeight),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().nestedScroll(queueScroll)
                        .verticalScroll(scrollState, enabled = scrollState.maxValue > 0),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.size(artworkSide), contentAlignment = Alignment.Center) { artwork() }
                    if (lyricLine == null) {
                        Spacer(Modifier.height(20.dp))
                    } else {
                        /*
                         * The line is held to the card's width and starts at the card's leading edge,
                         * so its first character lines up with the artwork rather than floating in the
                         * middle of a wider column.
                         */
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(artworkSide)) { lyricLine() }
                        Spacer(Modifier.height(10.dp))
                    }
                    CompositionLocalProvider(LocalCapsuleLightMenu provides onMenuClick) { details() }
                }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { artwork() }
            details()
        }
    }
}

@Composable
private fun CapsuleLightQueueDock(
    textColor: Color,
    enabled: Boolean,
    onExpandQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .width(44.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(textColor.copy(alpha = 0.22f))
                    .clickable(
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onExpandQueue,
                    ),
        )
    }
}

@Composable
internal fun CapsuleLightFavorite(
    liked: Boolean,
    textColor: Color,
    onToggleLike: () -> Unit,
    enabled: Boolean = true,
) {
    val favoriteInteraction = remember { MutableInteractionSource() }
    var userActionToken by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = favoriteInteraction,
                indication = null,
                role = Role.Button,
                onClick = {
                    userActionToken += 1
                    onToggleLike()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        CapsuleFavoriteIcon(
            liked = liked,
            interactionSource = favoriteInteraction,
            tint = if (liked) CapsuleFavoriteColors.selected(textColor) else textColor.copy(alpha = 0.72f),
            modifier = Modifier.size(28.dp),
            userActionToken = userActionToken,
        )
    }
}

/** One calm transport capsule: repeat, previous, orbit, next and menu. */
@Composable
internal fun CapsuleLightControls(
    textColor: Color,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    enabled: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    orbit: @Composable () -> Unit,
    onMenuClick: (() -> Unit)? = null,
    interactionEnabled: Boolean = true,
    order: List<CapsuleLightTransportItem> = CapsuleLightTransportBaseOrder,
    editable: Boolean = false,
    onOrderChange: (List<CapsuleLightTransportItem>) -> Unit = {},
    onOrderSettled: (List<CapsuleLightTransportItem>) -> Unit = {},
    onEditStarted: () -> Unit = {},
) {
    val menuAction = onMenuClick ?: LocalCapsuleLightMenu.current
    val transportSurface = textColor.copy(alpha = 0.035f)
    val transportOutline = textColor.copy(alpha = 0.16f)

    CapsuleLightReorderRow(
        order = order,
        editable = editable,
        weightFor = { item ->
            when (item) {
                CapsuleLightTransportItem.PLAY_PAUSE -> 1.38f
                else -> 1f
            }
        },
        onOrderChange = onOrderChange,
        onOrderSettled = onOrderSettled,
        onEditStarted = onEditStarted,
        dragHandleOnlyFor = { item ->
            item == CapsuleLightTransportItem.PLAY_PAUSE
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(84.dp)
                .clip(CapsuleLightPanelShape)
                .background(transportSurface)
                .border(1.dp, transportOutline, CapsuleLightPanelShape)
                .padding(horizontal = 4.dp),
    ) { item ->
        when (item) {
            CapsuleLightTransportItem.REPEAT -> {
                CapsuleLightTransportIcon(
                    iconRes = if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat,
                    contentDescription =
                        stringResource(
                            when (repeatMode) {
                                Player.REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                Player.REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> R.string.repeat_mode_off
                            },
                        ),
                    enabled = enabled && interactionEnabled,
                    active = repeatMode != Player.REPEAT_MODE_OFF,
                    textColor = textColor,
                    onClick = onRepeat,
                    modifier = Modifier.fillMaxWidth().semantics { selected = repeatMode != Player.REPEAT_MODE_OFF },
                    iconSize = 25,
                )
            }

            CapsuleLightTransportItem.PREVIOUS -> {
                CapsuleLightTransportIcon(
                    iconRes = R.drawable.skip_previous,
                    contentDescription = stringResource(androidx.media3.ui.R.string.exo_controls_previous_description),
                    enabled = enabled && interactionEnabled && canSkipPrevious,
                    active = true,
                    textColor = textColor,
                    onClick = onPrevious,
                    modifier = Modifier.fillMaxWidth(),
                    iconSize = 32,
                )
            }

            CapsuleLightTransportItem.PLAY_PAUSE -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    orbit()
                }
            }

            CapsuleLightTransportItem.NEXT -> {
                CapsuleLightTransportIcon(
                    iconRes = R.drawable.skip_next,
                    contentDescription = stringResource(androidx.media3.ui.R.string.exo_controls_next_description),
                    enabled = enabled && interactionEnabled && canSkipNext,
                    active = true,
                    textColor = textColor,
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth(),
                    iconSize = 32,
                )
            }

            CapsuleLightTransportItem.MENU -> {
                CapsuleLightTransportIcon(
                    iconRes = R.drawable.more_vert,
                    contentDescription = stringResource(R.string.more),
                    enabled = interactionEnabled,
                    active = true,
                    textColor = textColor,
                    onClick = menuAction,
                    modifier = Modifier.fillMaxWidth(),
                    iconSize = 27,
                )
            }
        }
    }
}

@Composable
private fun CapsuleLightTransportIcon(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    active: Boolean,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    iconSize: Int,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint =
                textColor.copy(
                    alpha =
                        when {
                            !enabled -> 0.24f
                            active -> 0.98f
                            else -> 0.48f
                        },
                ),
            modifier = Modifier.size(iconSize.dp),
        )
    }
}
