
 /** capsule fork
 * Animated source + bitrate control for Capsule Player.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioQualityKey
import com.nikhil.yt.playback.PlayerConnection
import com.nikhil.yt.playback.source.AudioSource
import com.nikhil.yt.playback.source.AudioSourceKey
import com.nikhil.yt.playback.source.AudioSourceManager
import com.nikhil.yt.playback.source.DeezerAudioQuality
import com.nikhil.yt.playback.source.DeezerAudioQualityKey
import com.nikhil.yt.utils.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun AudioSourceButton(
    playerConnection: PlayerConnection,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    val preferredName by
        remember(context) {
            context.dataStore.data.map { prefs ->
                prefs[AudioSourceKey] ?: AudioSource.YOUTUBE.name
            }
        }.collectAsState(initial = AudioSource.YOUTUBE.name)
    val preferred = AudioSource.fromPreference(preferredName)

    val youtubeQualityName by
        remember(context) {
            context.dataStore.data.map { prefs ->
                prefs[AudioQualityKey] ?: AudioQuality.AUTO.name
            }
        }.collectAsState(initial = AudioQuality.AUTO.name)
    val youtubeQuality =
        AudioQuality.values().firstOrNull { it.name.equals(youtubeQualityName, ignoreCase = true) }
            ?: AudioQuality.AUTO

    val deezerQualityName by
        remember(context) {
            context.dataStore.data.map { prefs ->
                prefs[DeezerAudioQualityKey] ?: DeezerAudioQuality.MP3_320.name
            }
        }.collectAsState(initial = DeezerAudioQuality.MP3_320.name)
    val deezerQuality = DeezerAudioQuality.fromPreference(deezerQualityName)

    val sourceState by AudioSourceManager.playbackState.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val currentFormat by playerConnection.currentFormat.collectAsState(initial = null)

    val stateForCurrent = sourceState.takeIf { it.mediaId == mediaMetadata?.id }
    val actual = stateForCurrent?.actual ?: AudioSource.YOUTUBE
    val resolving = stateForCurrent?.resolving == true

    val actualBitrate =
        when (actual) {
            AudioSource.YOUTUBE -> currentFormat?.bitrate
            AudioSource.DEEZER, AudioSource.AMAZON_MUSIC -> stateForCurrent?.bitrate
        }

    val targetQuality =
        when (preferred) {
            AudioSource.YOUTUBE -> youtubeQuality.badgeLabel()
            AudioSource.DEEZER -> deezerQuality.badge
            AudioSource.AMAZON_MUSIC -> "—"
        }

    val bitrateBadge = actualBitrate?.toKbpsLabel() ?: targetQuality
    val displayText =
        if (resolving) {
            "CHECKING DEEZER"
        } else {
            "${actual.shortDisplay()} · $bitrateBadge"
        }

    val infinite = rememberInfiniteTransition(label = "sourcePulse")
    val pulse by
        infinite.animateFloat(
            initialValue = 0.72f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(760),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "sourcePulseAlpha",
        )

    val pillShape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .widthIn(min = 154.dp, max = 228.dp)
                    .height(38.dp)
                    .clip(pillShape)
                    .background(textColor.copy(alpha = 0.045f))
                    .border(1.dp, textColor.copy(alpha = if (expanded) 0.30f else 0.14f), pillShape)
                    .clickable { expanded = true }
                    .animateContentSize()
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(if (resolving) 7.dp else 6.dp)
                        .graphicsLayer {
                            val animated = if (resolving) pulse else 1f
                            scaleX = animated
                            scaleY = animated
                            alpha = animated
                        }
                        .background(
                            color =
                                when {
                                    resolving -> textColor.copy(alpha = 0.92f)
                                    actual == preferred -> textColor.copy(alpha = 0.90f)
                                    preferred != AudioSource.YOUTUBE -> textColor.copy(alpha = 0.42f)
                                    else -> textColor.copy(alpha = 0.72f)
                                },
                            shape = CircleShape,
                        ),
            )
            Spacer(Modifier.width(8.dp))

            Crossfade(
                targetState = displayText,
                animationSpec = tween(180),
                label = "sourceText",
            ) { label ->
                Text(
                    text = label,
                    color = textColor.copy(alpha = 0.88f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }

            Spacer(Modifier.width(7.dp))
            Text(
                text = "⌄",
                color = textColor.copy(alpha = 0.50f),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier =
                Modifier
                    .width(306.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(22.dp),
                    ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(
                    text = "PLAYBACK SOURCE",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text =
                        when {
                            resolving -> "YouTube keeps playing while Deezer is checked"
                            preferred != actual -> "Preferred ${preferred.title} · actual ${actual.title}"
                            else -> "Playing from ${actual.title}"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ProviderMenuItem(
                title = "YouTube",
                subtitle = "Stable · original Capsule pipeline",
                selected = preferred == AudioSource.YOUTUBE,
                actual = actual == AudioSource.YOUTUBE,
                enabled = true,
                onClick = {
                    scope.launch {
                        context.dataStore.edit { it[AudioSourceKey] = AudioSource.YOUTUBE.name }
                        AudioSourceManager.onPreferredSourceChanged(AudioSource.YOUTUBE)
                        expanded = false
                        playerConnection.service.applyPreferredAudioSource(force = true)
                    }
                },
            )

            ProviderMenuItem(
                title = "Deezer",
                subtitle = "Experimental · background check · instant YouTube fallback",
                selected = preferred == AudioSource.DEEZER,
                actual = actual == AudioSource.DEEZER,
                enabled = true,
                onClick = {
                    scope.launch {
                        context.dataStore.edit { it[AudioSourceKey] = AudioSource.DEEZER.name }
                        AudioSourceManager.onPreferredSourceChanged(AudioSource.DEEZER)
                        expanded = false
                        playerConnection.service.applyPreferredAudioSource(force = true)
                    }
                },
            )

            ProviderMenuItem(
                title = "Amazon Music",
                subtitle = "Backend not enabled yet",
                selected = preferred == AudioSource.AMAZON_MUSIC,
                actual = actual == AudioSource.AMAZON_MUSIC,
                enabled = false,
                onClick = {},
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )

            Text(
                text = "QUALITY / BITRATE",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )

            when (preferred) {
                AudioSource.YOUTUBE -> {
                    AudioQuality.values().forEach { quality ->
                        QualityMenuItem(
                            title = quality.menuTitle(),
                            subtitle = quality.menuSubtitle(),
                            selected = youtubeQuality == quality,
                            onClick = {
                                scope.launch {
                                    context.dataStore.edit { it[AudioQualityKey] = quality.name }
                                    expanded = false
                                    playerConnection.service.refreshCurrentAudioSource()
                                }
                            },
                        )
                    }
                }

                AudioSource.DEEZER -> {
                    DeezerAudioQuality.values().forEach { quality ->
                        QualityMenuItem(
                            title = quality.title,
                            subtitle =
                                when (quality) {
                                    DeezerAudioQuality.MP3_128 -> "Fastest resolver target"
                                    DeezerAudioQuality.MP3_320 -> "Recommended target"
                                    DeezerAudioQuality.FLAC -> "Lossless target when a direct route exists"
                                },
                            selected = deezerQuality == quality,
                            onClick = {
                                scope.launch {
                                    context.dataStore.edit { it[DeezerAudioQualityKey] = quality.name }
                                    expanded = false
                                    playerConnection.service.applyPreferredAudioSource(force = true)
                                }
                            },
                        )
                    }
                }

                AudioSource.AMAZON_MUSIC -> Unit
            }

            val detail = stateForCurrent?.detail
            if (!detail.isNullOrBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f),
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ProviderMenuItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    actual: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f)
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title.take(1).uppercase(),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        color =
                            if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color =
                            if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 1f else 0.45f,
                        ),
                    )
                }
                if (actual) {
                    Text(
                        text = "LIVE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (selected) {
                    Text(
                        text = "PRIORITY",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun QualityMenuItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selected) "●" else "○",
                    color =
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = onClick,
    )
}

private fun Int.toKbpsLabel(): String =
    if (this > 0) {
        if (this >= 1_000_000) "${this / 1000}K" else "${(this / 1000f).toInt()}K"
    } else {
        "—"
    }

private fun AudioSource.shortDisplay(): String =
    when (this) {
        AudioSource.YOUTUBE -> "YOUTUBE"
        AudioSource.DEEZER -> "DEEZER"
        AudioSource.AMAZON_MUSIC -> "AMAZON"
    }

private fun AudioQuality.badgeLabel(): String =
    when (this) {
        AudioQuality.AUTO -> "AUTO"
        AudioQuality.LOW -> "LOW"
        AudioQuality.HIGH -> "HIGH"
        AudioQuality.HIGHEST -> "MAX"
    }

private fun AudioQuality.menuTitle(): String =
    when (this) {
        AudioQuality.AUTO -> "Auto"
        AudioQuality.LOW -> "Low"
        AudioQuality.HIGH -> "High"
        AudioQuality.HIGHEST -> "Highest"
    }

private fun AudioQuality.menuSubtitle(): String =
    when (this) {
        AudioQuality.AUTO -> "Automatic format selection"
        AudioQuality.LOW -> "Lower data usage"
        AudioQuality.HIGH -> "High quality YouTube format"
        AudioQuality.HIGHEST -> "Best available YouTube format"
    }
