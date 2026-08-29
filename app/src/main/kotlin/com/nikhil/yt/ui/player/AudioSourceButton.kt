/*
 * capsule fork
 * Source + bitrate selector for Capsule Player.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.ui.player

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

    val providerBadge =
        when {
            preferred == AudioSource.DEEZER && actual == AudioSource.YOUTUBE -> "DZ→YT"
            preferred == AudioSource.AMAZON_MUSIC && actual == AudioSource.YOUTUBE -> "AMZ→YT"
            actual == AudioSource.DEEZER -> "DEEZER"
            actual == AudioSource.AMAZON_MUSIC -> "AMAZON"
            else -> "YOUTUBE"
        }

    val actualBitrate =
        when (actual) {
            AudioSource.DEEZER -> stateForCurrent?.bitrate
            AudioSource.YOUTUBE -> currentFormat?.bitrate
            AudioSource.AMAZON_MUSIC -> stateForCurrent?.bitrate
        }

    val targetBadge =
        when (preferred) {
            AudioSource.YOUTUBE -> youtubeQuality.badgeLabel()
            AudioSource.DEEZER -> deezerQuality.badge
            AudioSource.AMAZON_MUSIC -> "—"
        }

    val bitrateBadge = actualBitrate?.toKbpsLabel() ?: targetBadge
    val shape = RoundedCornerShape(11.dp)

    Box(
        modifier = modifier.widthIn(min = 118.dp, max = 150.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .clip(shape)
                    .background(textColor.copy(alpha = 0.055f))
                    .border(1.dp, textColor.copy(alpha = 0.14f), shape)
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(5.dp)
                        .background(
                            color =
                                if (actual == preferred) {
                                    textColor.copy(alpha = 0.9f)
                                } else {
                                    textColor.copy(alpha = 0.38f)
                                },
                            shape = CircleShape,
                        ),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "$providerBadge · $bitrateBadge",
                color = textColor.copy(alpha = 0.88f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "▾",
                color = textColor.copy(alpha = 0.5f),
                fontSize = 9.sp,
                maxLines = 1,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier =
                Modifier
                    .width(286.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(18.dp),
                    ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "AUDIO SOURCE",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            ProviderMenuItem(
                title = "YouTube",
                subtitle = "Stable · Capsule core",
                selected = preferred == AudioSource.YOUTUBE,
                enabled = true,
                onClick = {
                    scope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[AudioSourceKey] = AudioSource.YOUTUBE.name
                        }
                        AudioSourceManager.onPreferredSourceChanged(AudioSource.YOUTUBE)
                        playerConnection.service.refreshCurrentAudioSource()
                    }
                },
            )
            ProviderMenuItem(
                title = "Deezer",
                subtitle = "Fast · YouTube fallback",
                selected = preferred == AudioSource.DEEZER,
                enabled = true,
                onClick = {
                    scope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[AudioSourceKey] = AudioSource.DEEZER.name
                        }
                        AudioSourceManager.onPreferredSourceChanged(AudioSource.DEEZER)
                        playerConnection.service.refreshCurrentAudioSource()
                    }
                },
            )
            ProviderMenuItem(
                title = "Amazon Music",
                subtitle = "Backend not enabled yet",
                selected = preferred == AudioSource.AMAZON_MUSIC,
                enabled = false,
                onClick = {},
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )

            Text(
                text = "QUALITY / BITRATE",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
            )

            when (preferred) {
                AudioSource.YOUTUBE -> {
                    YoutubeQualityItem(
                        title = "Auto",
                        subtitle = "Automatic format selection",
                        selected = youtubeQuality == AudioQuality.AUTO,
                        onSelect = {
                            scope.launch {
                                context.dataStore.edit { it[AudioQualityKey] = AudioQuality.AUTO.name }
                                expanded = false
                                playerConnection.service.refreshCurrentAudioSource()
                            }
                        },
                    )
                    YoutubeQualityItem(
                        title = "Low",
                        subtitle = "Lower data usage",
                        selected = youtubeQuality == AudioQuality.LOW,
                        onSelect = {
                            scope.launch {
                                context.dataStore.edit { it[AudioQualityKey] = AudioQuality.LOW.name }
                                expanded = false
                                playerConnection.service.refreshCurrentAudioSource()
                            }
                        },
                    )
                    YoutubeQualityItem(
                        title = "High",
                        subtitle = "High quality",
                        selected = youtubeQuality == AudioQuality.HIGH,
                        onSelect = {
                            scope.launch {
                                context.dataStore.edit { it[AudioQualityKey] = AudioQuality.HIGH.name }
                                expanded = false
                                playerConnection.service.refreshCurrentAudioSource()
                            }
                        },
                    )
                    YoutubeQualityItem(
                        title = "Highest",
                        subtitle = "Best available YouTube format",
                        selected = youtubeQuality == AudioQuality.HIGHEST,
                        onSelect = {
                            scope.launch {
                                context.dataStore.edit { it[AudioQualityKey] = AudioQuality.HIGHEST.name }
                                expanded = false
                                playerConnection.service.refreshCurrentAudioSource()
                            }
                        },
                    )
                }

                AudioSource.DEEZER -> {
                    DeezerAudioQuality.values().forEach { quality ->
                        QualityMenuItem(
                            title = quality.title,
                            selected = deezerQuality == quality,
                            enabled = true,
                            onClick = {
                                scope.launch {
                                    context.dataStore.edit { prefs ->
                                        prefs[DeezerAudioQualityKey] = quality.name
                                    }
                                    expanded = false
                                    playerConnection.service.refreshCurrentAudioSource()
                                }
                            },
                        )
                    }
                }

                AudioSource.AMAZON_MUSIC -> {
                    Text(
                        text = "Amazon Music quality becomes available when its playback backend is connected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }

            val detail = stateForCurrent?.detail
            if (!detail.isNullOrBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
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
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
                                },
                                CircleShape,
                            ),
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color =
                            if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 1f else 0.48f,
                        ),
                    )
                }
                if (selected) {
                    Text(
                        text = "ON",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun YoutubeQualityItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    QualityMenuItem(
        title = title,
        subtitle = subtitle,
        selected = selected,
        enabled = true,
        onClick = onSelect,
    )
}

@Composable
private fun QualityMenuItem(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (selected) "●" else "○",
                    color =
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color =
                            if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        onClick = onClick,
        enabled = enabled,
    )
}

private fun Int.toKbpsLabel(): String =
    if (this > 0) "${(this / 1000f).toInt()}K" else "—"

private fun AudioQuality.badgeLabel(): String =
    when (this) {
        AudioQuality.AUTO -> "AUTO"
        AudioQuality.LOW -> "LOW"
        AudioQuality.HIGH -> "HIGH"
        AudioQuality.HIGHEST -> "MAX"
    }
