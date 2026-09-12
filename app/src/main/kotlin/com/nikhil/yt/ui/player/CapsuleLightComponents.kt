package com.nikhil.yt.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (light) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse, modifier = Modifier.background(textColor.copy(alpha = 0.035f), CircleShape)) {
                    Icon(painterResource(R.drawable.expand_more), stringResource(R.string.capsule_collapse_player), tint = textColor)
                }
                IconButton(onClick = onMenuClick, modifier = Modifier.background(textColor.copy(alpha = 0.035f), CircleShape)) {
                    Icon(painterResource(R.drawable.more_vert), stringResource(R.string.more), tint = textColor)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (light) 32.dp else 22.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { artwork() }
        details()
    }
}

@Composable
internal fun CapsuleLightFavorite(liked: Boolean, textColor: Color, onToggleLike: () -> Unit) {
    IconButton(onClick = onToggleLike, modifier = Modifier.size(52.dp)) {
        Icon(
            painterResource(if (liked) R.drawable.favorite else R.drawable.favorite_border),
            contentDescription = stringResource(if (liked) R.string.action_remove_like else R.string.action_like),
            tint = if (liked) Color(0xFFE00038) else textColor,
            modifier = Modifier.size(32.dp),
        )
    }
}

/** One quiet transport row, with real queue shuffle/repeat and the Capsule orbit. */
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
    val shape = RoundedCornerShape(36.dp)
    Row(
        Modifier.fillMaxWidth().height(104.dp).clip(shape)
            .border(1.dp, textColor.copy(alpha = 0.16f), shape)
            .background(textColor.copy(alpha = 0.015f)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onShuffle, enabled = enabled, modifier = Modifier.weight(1f).semantics { selected = shuffleEnabled }) {
            Icon(painterResource(R.drawable.shuffle), stringResource(R.string.shuffle),
                tint = textColor.copy(alpha = if (!enabled) 0.25f else if (shuffleEnabled) 1f else 0.52f), modifier = Modifier.size(25.dp))
        }
        IconButton(onClick = onPrevious, enabled = enabled && canSkipPrevious, modifier = Modifier.weight(1f)) {
            Icon(painterResource(R.drawable.skip_previous), stringResource(androidx.media3.ui.R.string.exo_controls_previous_description),
                tint = textColor.copy(alpha = if (enabled && canSkipPrevious) 0.96f else 0.25f), modifier = Modifier.size(32.dp))
        }
        Box(Modifier.weight(1.35f), contentAlignment = Alignment.Center) { orbit() }
        IconButton(onClick = onNext, enabled = enabled && canSkipNext, modifier = Modifier.weight(1f)) {
            Icon(painterResource(R.drawable.skip_next), stringResource(androidx.media3.ui.R.string.exo_controls_next_description),
                tint = textColor.copy(alpha = if (enabled && canSkipNext) 0.96f else 0.25f), modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = onRepeat, enabled = enabled, modifier = Modifier.weight(1f).semantics { selected = repeatMode != Player.REPEAT_MODE_OFF }) {
            Icon(
                painterResource(if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat),
                stringResource(when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> R.string.repeat_mode_one
                    Player.REPEAT_MODE_ALL -> R.string.repeat_mode_all
                    else -> R.string.repeat_mode_off
                }),
                tint = textColor.copy(alpha = if (!enabled) 0.25f else if (repeatMode != Player.REPEAT_MODE_OFF) 1f else 0.52f),
                modifier = Modifier.size(25.dp),
            )
        }
    }
}
