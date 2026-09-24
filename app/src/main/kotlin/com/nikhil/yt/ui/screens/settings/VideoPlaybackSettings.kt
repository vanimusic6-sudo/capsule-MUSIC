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
import com.nikhil.yt.R
import androidx.compose.ui.res.stringResource
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

        PreferenceGroupTitle(title = stringResource(R.string.capsule_video_quality_title))

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
                stringResource(R.string.capsule_video_quality_explanation),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PreferenceGroupTitle(title = stringResource(R.string.capsule_video_mode_title))

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
                    text = stringResource(R.string.capsule_video_follow_switch),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text =
                        stringResource(R.string.capsule_video_follow_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(96.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.capsule_video_settings_title)) },
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
                        CapsuleVideoQuality.AUTO -> stringResource(R.string.capsule_video_auto)
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
                        CapsuleVideoQuality.AUTO -> stringResource(R.string.capsule_video_best_quality)
                        CapsuleVideoQuality.P360 -> stringResource(R.string.capsule_video_low_data)
                        CapsuleVideoQuality.P480 -> stringResource(R.string.capsule_video_balanced)
                        CapsuleVideoQuality.P720 -> stringResource(R.string.capsule_video_high_quality)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
