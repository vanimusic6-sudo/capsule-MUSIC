package com.nikhil.yt.ui.player

import com.nikhil.yt.ui.component.CapsuleFavoriteIcon
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsulePlayerDesign

private val LocalCapsuleLightMenu = staticCompositionLocalOf<() -> Unit> { {} }

/** Soft corners for Capsule Light's low-contrast controls. */
internal val CapsuleLightPanelShape = RoundedCornerShape(14.dp)

/** Both designs host the same artwork, metadata and playback actions. */
@Composable
internal fun CapsulePlayerLayout(
    design: CapsulePlayerDesign,
    textColor: Color,
    onCollapse: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    onExpandQueue: () -> Unit = {},
    artwork: @Composable () -> Unit,
    details: @Composable () -> Unit,
) {
    val light = design == CapsulePlayerDesign.LIGHT
    if (light) {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
            val detailsSpace = 320.dp * fontScale
            val artworkSide = minOf(
                (maxWidth - 48.dp).coerceAtLeast(120.dp),
                (maxHeight - detailsSpace).coerceIn(180.dp, 420.dp),
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
            Column(
                modifier = Modifier.fillMaxWidth().nestedScroll(queueScroll)
                    .verticalScroll(scrollState, enabled = scrollState.maxValue > 0),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.size(artworkSide), contentAlignment = Alignment.Center) { artwork() }
                Spacer(Modifier.height(20.dp))
                CompositionLocalProvider(LocalCapsuleLightMenu provides onMenuClick) { details() }
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
) {
    val favoriteInteraction = remember { MutableInteractionSource() }
    IconButton(
        onClick = onToggleLike,
        interactionSource = favoriteInteraction,
        modifier = Modifier.size(48.dp),
    ) {
        CapsuleFavoriteIcon(
            liked = liked,
            interactionSource = favoriteInteraction,
            tint = if (liked) CapsuleFavoriteColors.selected(textColor) else textColor.copy(alpha = 0.72f),
            modifier = Modifier.size(28.dp),
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

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .padding(horizontal = 2.dp),
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
