package com.nikhil.yt.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsulePlayerDesign

private val LocalCapsuleLightMenu = staticCompositionLocalOf<() -> Unit> { {} }

/** Shared radius for Capsule Light's two outlined control panels. */
internal val CapsuleLightPanelShape = RoundedCornerShape(18.dp)

/** Both designs host the same artwork, metadata and playback actions. */
@Composable
internal fun CapsulePlayerLayout(
    design: CapsulePlayerDesign,
    textColor: Color,
    onCollapse: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
    details: @Composable () -> Unit,
) {
    val light = design == CapsulePlayerDesign.LIGHT
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (light) {
            /* Capsule Light has no floating header controls. The artwork starts
             * almost immediately below the system inset and remains the dominant
             * element, matching the compact reference layout. */
            Spacer(Modifier.height(4.dp))

            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                val targetSide =
                    when {
                        screenHeight < 700.dp -> 245.dp
                        screenHeight < 760.dp -> 290.dp
                        screenHeight < 840.dp -> 335.dp
                        else -> 380.dp
                    }

                val artworkSide = minOf(maxWidth, targetSide)

                Box(
                    modifier = Modifier.size(artworkSide),
                    contentAlignment = Alignment.Center,
                ) {
                    artwork()
                }
            }

            Spacer(Modifier.height(14.dp))
            CompositionLocalProvider(LocalCapsuleLightMenu provides onMenuClick) {
                details()
            }
        } else {
            Spacer(Modifier.height(10.dp))

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                artwork()
            }

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
    val scale by
        animateFloatAsState(
            targetValue = if (liked) 1.08f else 1f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "capsuleLightFavoriteScale",
        )

    val tint by
        animateColorAsState(
            targetValue = if (liked) Color(0xFFFF174F) else textColor.copy(alpha = 0.92f),
            label = "capsuleLightFavoriteTint",
        )

    IconButton(
        onClick = onToggleLike,
        modifier = Modifier.size(52.dp),
    ) {
        Icon(
            painter = painterResource(if (liked) R.drawable.favorite else R.drawable.favorite_border),
            contentDescription = stringResource(if (liked) R.string.action_remove_like else R.string.action_like),
            tint = tint,
            modifier =
                Modifier
                    .size(32.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
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
    val panelBrush =
        Brush.verticalGradient(
            listOf(
                textColor.copy(alpha = 0.035f),
                textColor.copy(alpha = 0.012f),
            ),
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(CapsuleLightPanelShape)
                .background(panelBrush)
                .border(1.dp, textColor.copy(alpha = 0.14f), CapsuleLightPanelShape)
                .padding(horizontal = 8.dp),
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
