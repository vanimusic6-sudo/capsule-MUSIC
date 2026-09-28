/**
 * Capsule MUSIC
 * Nudging the lyrics into line, from the player.
 * GPL-3.0
 */

package com.nikhil.yt.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.ui.screens.settings.LyricsPosition
import com.nikhil.yt.constants.LyricsSyncOffsetKey
import com.nikhil.yt.constants.LyricsTextPositionKey
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference

/** One press. Small enough to home in on a bad file, big enough to be worth pressing. */
private const val OFFSET_STEP_MS = 100

/**
 * The furthest the offset may go either way.
 *
 * Past a couple of seconds a file is not offset, it is the wrong file — and letting someone drag it
 * to a minute out just lets them lose the lyrics entirely with no obvious way back.
 */
internal const val OFFSET_LIMIT_MS = 3_000

/**
 * Where the lyrics sit, and how far ahead of the music they run.
 *
 * Both belong here rather than only in settings: you find out a file is early while it is playing,
 * and fixing it three screens away means leaving the song to do it.
 *
 * There is no scroll speed, and there cannot be one — synced lyrics are pinned to timestamps, so
 * nothing about them scrolls at a rate that could be changed. A file that is uniformly early or
 * late is a constant offset, and a file that drifts further out as it goes is one with the wrong
 * timings, which no single number can rescue.
 */
@Composable
internal fun LyricsTuning() {
    val (position, onPositionChange) =
        rememberEnumPreference(LyricsTextPositionKey, LyricsPosition.LEFT)
    val (offsetMs, onOffsetChange) = rememberPreference(LyricsSyncOffsetKey, defaultValue = 0)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.lyrics_text_position),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(8.dp))

        val positions = listOf(LyricsPosition.LEFT, LyricsPosition.CENTER, LyricsPosition.RIGHT)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            positions.forEach { value ->
                val selected = position == value
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                            )
                            .clickable {
                                onPositionChange(value)
                            }
                            .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            stringResource(
                                when (value) {
                                    LyricsPosition.LEFT -> R.string.left
                                    LyricsPosition.CENTER -> R.string.center
                                    LyricsPosition.RIGHT -> R.string.right
                                },
                            ).replaceFirstChar { it.lowercase() },
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.lyrics_sync_offset),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                FilledTonalIconButton(
                    onClick = { onOffsetChange(clampOffset(offsetMs - OFFSET_STEP_MS)) },
                    enabled = offsetMs > -OFFSET_LIMIT_MS,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.remove),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.lyrics_sync_offset_value, offsetMs / 1000f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp).widthIn(min = 72.dp),
                )

                FilledTonalIconButton(
                    onClick = { onOffsetChange(clampOffset(offsetMs + OFFSET_STEP_MS)) },
                    enabled = offsetMs < OFFSET_LIMIT_MS,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.lyrics_sync_offset_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )

            if (offsetMs != 0) {
                TextButton(onClick = { onOffsetChange(0) }) {
                    Text(stringResource(R.string.reset))
                }
            }
        }
    }
}

/** Keeps a stored value in range too, not only a pressed one. */
internal fun clampOffset(offsetMs: Int): Int = offsetMs.coerceIn(-OFFSET_LIMIT_MS, OFFSET_LIMIT_MS)
