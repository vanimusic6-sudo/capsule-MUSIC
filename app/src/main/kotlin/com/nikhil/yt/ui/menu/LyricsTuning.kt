/**
 * Capsule MUSIC — compact, always-visible lyrics positioning and sync.
 * GPL-3.0.
 */
package com.nikhil.yt.ui.menu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.constants.LyricsSyncOffsetKey
import com.nikhil.yt.constants.LyricsTextPositionKey
import com.nikhil.yt.ui.component.CapsuleMenuBlack
import com.nikhil.yt.ui.component.CapsuleMenuTileGap
import com.nikhil.yt.ui.component.CapsuleMenuTileShape
import com.nikhil.yt.ui.screens.settings.LyricsPosition
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference

private const val OFFSET_STEP_MS = 100
internal const val OFFSET_LIMIT_MS = 3_000
private val LyricsTuningTileHeight = 52.dp

/** Single, bounded, non-expanding control group. No modal inside a modal. */
@Composable
internal fun LyricsTuning() {
    val (position, onPositionChange) =
        rememberEnumPreference(LyricsTextPositionKey, LyricsPosition.LEFT)
    val (storedOffsetMs, onOffsetChange) = rememberPreference(LyricsSyncOffsetKey, defaultValue = 0)
    val offsetMs = clampOffset(storedOffsetMs)
    val positions = listOf(LyricsPosition.LEFT, LyricsPosition.CENTER, LyricsPosition.RIGHT)
    val ink = MaterialTheme.colorScheme.onSurface
    val mutedInk = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedOutline = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CapsuleMenuTileGap),
    ) {
        // Three independent, equally sized tiles, not one merged segmented bar.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CapsuleMenuTileGap),
        ) {
            positions.forEach { value ->
                val selected = position == value
                Surface(
                    onClick = { onPositionChange(value) },
                    modifier = Modifier.weight(1f).height(LyricsTuningTileHeight),
                    shape = CapsuleMenuTileShape,
                    color = CapsuleMenuBlack,
                    border = if (selected) BorderStroke(1.dp, selectedOutline) else null,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(
                                when (value) {
                                    LyricsPosition.LEFT -> R.string.left
                                    LyricsPosition.CENTER -> R.string.center
                                    LyricsPosition.RIGHT -> R.string.right
                                },
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) ink else mutedInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CapsuleMenuTileGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = { onOffsetChange(clampOffset(offsetMs - OFFSET_STEP_MS)) },
                enabled = offsetMs > -OFFSET_LIMIT_MS,
                modifier = Modifier.size(LyricsTuningTileHeight),
                shape = CapsuleMenuTileShape,
                color = CapsuleMenuBlack,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.remove),
                        contentDescription = stringResource(R.string.lyrics_sync_offset),
                        tint = mutedInk,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            // The numeric value is read-only. Adjustments are direct; no
            // offset dialog or nested popup is required to complete the task.
            Surface(
                modifier = Modifier.weight(1f).height(LyricsTuningTileHeight),
                shape = CapsuleMenuTileShape,
                color = CapsuleMenuBlack,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_sync_offset_value, offsetMs / 1000f),
                        style = MaterialTheme.typography.titleSmall,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                onClick = { onOffsetChange(clampOffset(offsetMs + OFFSET_STEP_MS)) },
                enabled = offsetMs < OFFSET_LIMIT_MS,
                modifier = Modifier.size(LyricsTuningTileHeight),
                shape = CapsuleMenuTileShape,
                color = CapsuleMenuBlack,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = stringResource(R.string.lyrics_sync_offset),
                        tint = mutedInk,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Surface(
                onClick = { onOffsetChange(0) },
                enabled = offsetMs != 0,
                modifier = Modifier.width(64.dp).height(LyricsTuningTileHeight),
                shape = CapsuleMenuTileShape,
                color = CapsuleMenuBlack,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.reset),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (offsetMs == 0) mutedInk.copy(alpha = 0.48f) else ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun clampOffset(offsetMs: Int): Int = offsetMs.coerceIn(-OFFSET_LIMIT_MS, OFFSET_LIMIT_MS)
