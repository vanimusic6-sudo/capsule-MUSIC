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
    lightOrder: List<CapsuleLightElement> = CapsuleLightBaseOrder,
    onLightOrderChange: (List<CapsuleLightElement>) -> Unit = {},
    onLightOrderSettled: (List<CapsuleLightElement>) -> Unit = {},
    onLightEditStarted: () -> Unit = {},
    /**
     * Optional block renderer used by the first Capsule "clay" editor.
     *
     * When supplied, Light is rendered as reorderable slots instead of the fixed artwork/details
     * stack. Dense/Immersive and old call sites keep the exact legacy path below.
     */
    lightElementContent: (@Composable (CapsuleLightElement, Dp, Boolean) -> Unit)? = null,
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
            // Square. Both ends are clamped, so a short or fontScale-heavy window can never ask
            // for a negative or unbounded card.
            val artworkSide = minOf(
                (maxWidth - 48.dp).coerceAtLeast(120.dp),
                (maxHeight - detailsSpace).coerceIn(160.dp, 360.dp),
            )
            val scrollState = rememberScrollState()
            val openQueue by rememberUpdatedState(onExpandQueue)
            val queueThreshold = with(LocalDensity.current) { 64.dp.toPx() }
            // A downward pull past the top keeps Capsule's existing queue gesture.
            // Scrolling back through the controls must not open the queue.
            val queueScroll = remember(scrollState, queueThreshold) {
                object : NestedScrollConnection {
                    var pulled = 0f
                    var opened = false

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
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
                        val wasPulling = pulled > 0f || opened
                        pulled = 0f
                        opened = false
                        return if (wasPulling) Velocity(0f, available.y) else Velocity.Zero
                    }
                }
            }
            if (lightElementContent != null) {
                CompositionLocalProvider(LocalCapsuleLightMenu provides onMenuClick) {
                    CapsuleLightClayLayout(
                        order = lightOrder,
                        editable = lightEditorEnabled,
                        scrollState = scrollState,
                        onOrderChange = onLightOrderChange,
                        onOrderSettled = onLightOrderSettled,
                        onEditStarted = onLightEditStarted,
                        modifier = Modifier.fillMaxWidth().nestedScroll(queueScroll),
                    ) { element, insidePanel ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            lightElementContent(element, artworkSide, insidePanel)
                        }
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
internal fun CapsuleLightFavorite(
    liked: Boolean,
    textColor: Color,
    onToggleLike: () -> Unit,
internal fun CapsuleLightFavorite(
    liked: Boolean,
    textColor: Color,
    onToggleLike: () -> Unit,
) {
    val favoriteInteraction = remember { MutableInteractionSource() }
    var userActionToken by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
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
) {
    val menuAction = onMenuClick ?: LocalCapsuleLightMenu.current
    val transportSurface = textColor.copy(alpha = 0.035f)
    val transportOutline = textColor.copy(alpha = 0.16f)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(84.dp)
                .clip(CapsuleLightPanelShape)
                .background(transportSurface)
                .border(1.dp, transportOutline, CapsuleLightPanelShape)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            enabled = enabled,
            active = repeatMode != Player.REPEAT_MODE_OFF,
            textColor = textColor,
            onClick = onRepeat,
            modifier = Modifier.weight(1f).semantics { selected = repeatMode != Player.REPEAT_MODE_OFF },
            iconSize = 25,
        )

        CapsuleLightTransportIcon(
            iconRes = R.drawable.skip_previous,
            contentDescription = stringResource(androidx.media3.ui.R.string.exo_controls_previous_description),
            enabled = enabled && canSkipPrevious,
            active = true,
            textColor = textColor,
            onClick = onPrevious,
            modifier = Modifier.weight(1f),
            iconSize = 32,
        )

        Box(
            modifier = Modifier.weight(1.38f),
            contentAlignment = Alignment.Center,
        ) {
            orbit()
        }

        CapsuleLightTransportIcon(
            iconRes = R.drawable.skip_next,
            contentDescription = stringResource(androidx.media3.ui.R.string.exo_controls_next_description),
            enabled = enabled && canSkipNext,
            active = true,
            textColor = textColor,
            onClick = onNext,
            modifier = Modifier.weight(1f),
            iconSize = 32,
        )

        CapsuleLightTransportIcon(
            iconRes = R.drawable.more_vert,
            contentDescription = stringResource(R.string.more),
            enabled = true,
            active = true,
            textColor = textColor,
            onClick = menuAction,
            modifier = Modifier.weight(1f),
            iconSize = 27,
        )
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
