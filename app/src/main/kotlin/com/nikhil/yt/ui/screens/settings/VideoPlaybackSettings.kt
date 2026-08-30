/*
 * Capsule MUSIC
 * Video playback settings.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.constants.CapsuleVideoQuality
import com.nikhil.yt.constants.CapsuleVideoQualityKey
import com.nikhil.yt.ui.component.IconButton
import com.nikhil.yt.ui.component.PreferenceGroupTitle
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.rememberEnumPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlaybackSettings(
    navController: NavController,
) {
    val (videoQuality, onVideoQualityChange) =
        rememberEnumPreference(
            CapsuleVideoQualityKey,
            defaultValue = CapsuleVideoQuality.AUTO,
        )

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        PreferenceGroupTitle(title = "Video quality")

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                CapsuleVideoQuality.entries.forEachIndexed { index, quality ->
                    VideoQualityRow(
                        quality = quality,
                        selected = quality == videoQuality,
                        onClick = { onVideoQualityChange(quality) },
                    )

                    if (index != CapsuleVideoQuality.entries.lastIndex) {
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp),
                        )
                    }
                }
            }
        }

        Text(
            text =
                "Auto chooses the highest validated video up to 720p. " +
                    "480p and 720p use separate YouTube video/audio streams when needed.",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PreferenceGroupTitle(title = "Video mode")

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "Follow the player switch",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text =
                        "After you select VIDEO in the player, following songs automatically " +
                            "try to open their official clip. If a trustworthy clip is not found, " +
                            "audio keeps playing and the switch shows VIDEO N/A.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(96.dp))
    }

    TopAppBar(
        title = { Text("Video playback") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(com.nikhil.yt.R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun VideoQualityRow(
    quality: CapsuleVideoQuality,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )

        Column(
            Modifier.padding(start = 14.dp),
        ) {
            Text(
                text =
                    when (quality) {
                        CapsuleVideoQuality.AUTO -> "Auto"
                        CapsuleVideoQuality.P360 -> "360p"
                        CapsuleVideoQuality.P480 -> "480p"
                        CapsuleVideoQuality.P720 -> "720p"
                    },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text =
                    when (quality) {
                        CapsuleVideoQuality.AUTO -> "Best validated quality up to 720p"
                        CapsuleVideoQuality.P360 -> "Lower data usage"
                        CapsuleVideoQuality.P480 -> "Balanced quality"
                        CapsuleVideoQuality.P720 -> "High quality"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
