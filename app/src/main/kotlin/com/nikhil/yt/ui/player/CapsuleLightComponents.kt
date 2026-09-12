package com.nikhil.yt.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(62.dp)
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsuleLightHeaderButton(
                    iconRes = R.drawable.expand_more,
                    contentDescription = stringResource(R.string.capsule_collapse_player),
                    textColor = textColor,
                    onClick = onCollapse,
                )
                CapsuleLightHeaderButton(
                    iconRes = R.drawable.more_vert,
                    contentDescription = stringResource(R.string.more),
                    textColor = textColor,
                    onClick = onMenuClick,
                )
            }

            Spacer(Modifier.height(10.dp))

            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                val targetSide =
                    when {
                        screenHeight < 700.dp -> 292.dp
                        screenHeight < 760.dp -> 320.dp
                        screenHeight < 840.dp -> 348.dp
                        else -> 370.dp
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
            details()
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
private fun CapsuleLightHeaderButton(
    iconRes: Int,
    contentDescription: String,
    textColor: Color,
    onClick: () -> Unit,
) {
    val shape = CircleShape

    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .size(48.dp)
                .clip(shape)
                .border(1.dp, textColor.copy(alpha = 0.09f), shape)
                .background(Color.Black.copy(alpha = 0.16f)),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = textColor.copy(alpha = 0.96f),
            modifier = Modifier.size(24.dp),
        )
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

/** One calm transport capsule with real queue shuffle/repeat and the Capsule orbit. */
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
) {
    val shape = RoundedCornerShape(38.dp)
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
                .clip(shape)
                .background(panelBrush)
                .border(1.dp, textColor.copy(alpha = 0.14f), shape)
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsuleLightTransportIcon(
            iconRes = R.drawable.shuffle,
            contentDescription = stringResource(R.string.shuffle),
            enabled = enabled,
            active = shuffleEnabled,
            textColor = textColor,
            onClick = onShuffle,
            modifier = Modifier.weight(1f).semantics { selected = shuffleEnabled },
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
