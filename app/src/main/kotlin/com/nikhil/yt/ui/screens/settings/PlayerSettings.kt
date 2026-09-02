/*
 * Capsule MUSIC
 * Based on Velune by Nikhil
 * Licensed Under GPL-3.0
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.constants.ArtistSeparatorsKey
import com.nikhil.yt.constants.AudioCrossfadeDurationKey
import com.nikhil.yt.constants.AudioNormalizationKey
import com.nikhil.yt.constants.AudioOffload
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioQualityKey
import com.nikhil.yt.constants.AudioStreamPolicy
import com.nikhil.yt.constants.AudioStreamPolicyKey
import com.nikhil.yt.constants.AutoDownloadOnLikeKey
import com.nikhil.yt.constants.AutoSkipNextOnErrorKey
import com.nikhil.yt.constants.AutoStartOnBluetoothKey
import com.nikhil.yt.constants.HistoryDuration
import com.nikhil.yt.constants.NetworkMeteredKey
import com.nikhil.yt.constants.PauseOnDeviceMuteKey
import com.nikhil.yt.constants.PermanentShuffleKey
import com.nikhil.yt.constants.PersistentQueueKey
import com.nikhil.yt.constants.SeekExtraSeconds
import com.nikhil.yt.constants.SkipSilenceKey
import com.nikhil.yt.constants.StopMusicOnTaskClearKey
import com.nikhil.yt.innertube.models.YouTubeClientUpstream
import com.nikhil.yt.ui.component.ArtistSeparatorsDialog
import com.nikhil.yt.ui.component.CrossfadeSliderPreference
import com.nikhil.yt.ui.component.EnumListPreference
import com.nikhil.yt.ui.component.IconButton
import com.nikhil.yt.ui.component.ListDialog
import com.nikhil.yt.ui.component.PreferenceEntry
import com.nikhil.yt.ui.component.PreferenceGroupTitle
import com.nikhil.yt.ui.component.SliderPreference
import com.nikhil.yt.ui.component.SwitchPreference
import com.nikhil.yt.ui.component.TagsManagementDialog
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference

private fun AudioStreamPolicy.title(): String =
    when (this) {
        AudioStreamPolicy.AUTO_SAFE -> "Safe automatic"
        AudioStreamPolicy.VISIONOS ->
            "visionOS ${YouTubeClientUpstream.VISIONOS_VERSION}"
        AudioStreamPolicy.WEB_EMBEDDED ->
            "Web Embedded ${YouTubeClientUpstream.WEB_EMBEDDED_VERSION}"
        AudioStreamPolicy.IOS ->
            "iOS ${YouTubeClientUpstream.IOS_VERSION}"
        AudioStreamPolicy.IOS_MUSIC -> "iOS Music"
        AudioStreamPolicy.TV_DOWNGRADED ->
            "TV compatibility ${YouTubeClientUpstream.TV_DOWNGRADED_VERSION}"
        AudioStreamPolicy.TVHTML5 ->
            "TV HTML5 ${YouTubeClientUpstream.TV_VERSION}"
    }

private fun AudioStreamPolicy.description(): String =
    when (this) {
        AudioStreamPolicy.AUTO_SAFE ->
            "Recommended. visionOS → Web Embedded → TV compatibility → TV. " +
                "Clients that currently require a GVS PO-token are skipped."

        AudioStreamPolicy.VISIONOS ->
            "Prefer the current visionOS identity, then use the reviewed safe fallbacks. " +
                "This is what field runs resolve fastest."

        AudioStreamPolicy.WEB_EMBEDDED ->
            "Prefer the anonymous Web Embedded identity, then use visionOS and TV fallbacks."

        AudioStreamPolicy.IOS ->
            "Manual compatibility mode. Current iOS media URLs can require a real PO-token."

        AudioStreamPolicy.IOS_MUSIC ->
            "Manual compatibility mode. The identity is pinned and can require a real PO-token."

        AudioStreamPolicy.TV_DOWNGRADED ->
            "Prefer yt-dlp's downgraded TV compatibility identity, then use safe fallbacks."

        AudioStreamPolicy.TVHTML5 ->
            "Prefer the current TV HTML5 identity, then use safe fallbacks."
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (audioQuality, onAudioQualityChange) =
        rememberEnumPreference(
            AudioQualityKey,
            defaultValue = AudioQuality.AUTO,
        )

    /*
     * This is intentionally a new setting key.
     *
     * Old values such as ANDROID_VR / IOS / ANDROID_MUSIC are not migrated
     * automatically because their upstream playback requirements changed.
     * Everyone starts on AUTO_SAFE after the new core is installed.
     */
    val (audioStreamPolicy, onAudioStreamPolicyChange) =
        rememberEnumPreference(
            AudioStreamPolicyKey,
            defaultValue = AudioStreamPolicy.AUTO_SAFE,
        )

    val (networkMetered, onNetworkMeteredChange) =
        rememberPreference(
            NetworkMeteredKey,
            defaultValue = true,
        )
    val (persistentQueue, onPersistentQueueChange) =
        rememberPreference(
            PersistentQueueKey,
            defaultValue = true,
        )
    val (permanentShuffle, onPermanentShuffleChange) =
        rememberPreference(
            PermanentShuffleKey,
            defaultValue = false,
        )
    val (skipSilence, onSkipSilenceChange) =
        rememberPreference(
            SkipSilenceKey,
            defaultValue = false,
        )
    val (audioNormalization, onAudioNormalizationChange) =
        rememberPreference(
            AudioNormalizationKey,
            defaultValue = true,
        )
    val (audioOffload, onAudioOffloadChange) =
        rememberPreference(
            AudioOffload,
            defaultValue = false,
        )
    val (seekExtraSeconds, onSeekExtraSeconds) =
        rememberPreference(
            SeekExtraSeconds,
            defaultValue = false,
        )
    val (autoDownloadOnLike, onAutoDownloadOnLikeChange) =
        rememberPreference(
            AutoDownloadOnLikeKey,
            defaultValue = false,
        )
    val (autoSkipNextOnError, onAutoSkipNextOnErrorChange) =
        rememberPreference(
            AutoSkipNextOnErrorKey,
            defaultValue = false,
        )
    val (pauseOnDeviceMute, onPauseOnDeviceMuteChange) =
        rememberPreference(
            PauseOnDeviceMuteKey,
            defaultValue = false,
        )
    val (autoStartOnBluetooth, onAutoStartOnBluetoothChange) =
        rememberPreference(
            AutoStartOnBluetoothKey,
            defaultValue = false,
        )
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) =
        rememberPreference(
            StopMusicOnTaskClearKey,
            defaultValue = false,
        )
    val (historyDuration, onHistoryDurationChange) =
        rememberPreference(
            HistoryDuration,
            defaultValue = 30f,
        )
    val (audioCrossfadeSeconds, onAudioCrossfadeSecondsChange) =
        rememberPreference(
            AudioCrossfadeDurationKey,
            defaultValue = 0,
        )
    val (artistSeparators, onArtistSeparatorsChange) =
        rememberPreference(
            ArtistSeparatorsKey,
            defaultValue = ",;/&",
        )

    var showArtistSeparatorsDialog by remember { mutableStateOf(false) }
    var showTagsManagementDialog by remember { mutableStateOf(false) }
    var showAudioStreamPolicyDialog by remember { mutableStateOf(false) }
    val database = LocalDatabase.current

    if (showArtistSeparatorsDialog) {
        ArtistSeparatorsDialog(
            currentSeparators = artistSeparators,
            onDismiss = { showArtistSeparatorsDialog = false },
            onSave = { newSeparators ->
                onArtistSeparatorsChange(newSeparators)
                showArtistSeparatorsDialog = false
            },
        )
    }

    if (showTagsManagementDialog) {
        TagsManagementDialog(
            database = database,
            onDismiss = { showTagsManagementDialog = false },
        )
    }

    if (showAudioStreamPolicyDialog) {
        ListDialog(
            onDismiss = { showAudioStreamPolicyDialog = false },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            items(AudioStreamPolicy.entries) { value ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onAudioStreamPolicyChange(value)
                                showAudioStreamPolicyDialog = false
                            }
                            .padding(
                                horizontal = 16.dp,
                                vertical = 12.dp,
                            ),
                ) {
                    RadioButton(
                        selected = value == audioStreamPolicy,
                        onClick = null,
                    )

                    Column(
                        modifier = Modifier.padding(start = 16.dp),
                    ) {
                        Text(
                            text = value.title(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = value.description(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal +
                        WindowInsetsSides.Bottom,
                ),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top,
                ),
            ),
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.player),
        )

        EnumListPreference(
            title = { Text(stringResource(R.string.audio_quality)) },
            icon = {
                Icon(
                    painterResource(R.drawable.graphic_eq),
                    null,
                )
            },
            selectedValue = audioQuality,
            onValueSelected = onAudioQualityChange,
            valueText = {
                when (it) {
                    AudioQuality.HIGHEST ->
                        stringResource(R.string.audio_quality_max)
                    AudioQuality.HIGH ->
                        stringResource(R.string.audio_quality_high)
                    AudioQuality.AUTO ->
                        stringResource(R.string.audio_quality_auto)
                    AudioQuality.LOW ->
                        stringResource(R.string.audio_quality_low)
                }
            },
        )

        PreferenceEntry(
            title = { Text("AUDIO stream policy") },
            description =
                "${audioStreamPolicy.title()} • " +
                    "upstream ${YouTubeClientUpstream.SOURCE_SNAPSHOT}",
            icon = {
                Icon(
                    painterResource(R.drawable.integration),
                    null,
                )
            },
            onClick = {
                showAudioStreamPolicyDialog = true
            },
        )

        Text(
            text =
                "Capsule Safe AUDIO Core uses only the reviewed client allowlist. " +
                    "Android VR and Android Music remain hidden because their current " +
                    "upstream PO-token requirements are different. " +
                    "Client identities are synchronized by CI, not changed live during playback.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier =
                Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 8.dp,
                ),
        )

        SwitchPreference(
            title = {
                Text(stringResource(R.string.network_metered_title))
            },
            description =
                stringResource(
                    R.string.network_metered_description,
                ),
            icon = {
                Icon(
                    painterResource(R.drawable.android_cell),
                    null,
                )
            },
            checked = networkMetered,
            onCheckedChange = onNetworkMeteredChange,
        )

        SliderPreference(
            title = {
                Text(stringResource(R.string.history_duration))
            },
            icon = {
                Icon(
                    painterResource(R.drawable.history),
                    null,
                )
            },
            value = historyDuration,
            onValueChange = onHistoryDurationChange,
        )

        CrossfadeSliderPreference(
            value = audioCrossfadeSeconds,
            onValueChange = onAudioCrossfadeSecondsChange,
            isEnabled = !audioOffload,
        )

        SwitchPreference(
            title = {
                Text(stringResource(R.string.skip_silence))
            },
            icon = {
                Icon(
                    painterResource(R.drawable.fast_forward),
                    null,
                )
            },
            checked = skipSilence,
            onCheckedChange = onSkipSilenceChange,
            isEnabled = !audioOffload,
        )

        SwitchPreference(
            title = {
                Text(stringResource(R.string.audio_normalization))
            },
            icon = {
                Icon(
                    painterResource(R.drawable.volume_up),
                    null,
                )
            },
            checked = audioNormalization,
            onCheckedChange = onAudioNormalizationChange,
        )

        SwitchPreference(
            title = {
                Text(stringResource(R.string.audio_offload))
            },
            description =
                stringResource(R.string.audio_offload_desc),
            icon = {
                Icon(
                    painterResource(R.drawable.speed),
                    null,
                )
            },
            checked = audioOffload,
            onCheckedChange = { enabled ->
                onAudioOffloadChange(enabled)
                if (enabled) {
                    onSkipSilenceChange(false)
                }
            },
        )

        SwitchPreference(
            title = {
                Text(
                    stringResource(
                        R.string.seek_seconds_addup,
                    ),
                )
            },
            description =
                stringResource(
                    R.string.seek_seconds_addup_description,
                ),
            icon = {
                Icon(
                    painterResource(R.drawable.arrow_forward),
                    null,
                )
            },
            checked = seekExtraSeconds,
            onCheckedChange = onSeekExtraSeconds,
        )

        SwitchPreference(
            title = {
                Text(
                    stringResource(
                        R.string.pause_on_device_mute,
                    ),
                )
            },
            description =
                stringResource(
                    R.string.pause_on_device_mute_desc,
                ),
            icon = {
                Icon(
                    painterResource(R.drawable.volume_off),
                    null,
                )
            },
            checked = pauseOnDeviceMute,
            onCheckedChange = onPauseOnDeviceMuteChange,
        )

        SwitchPreference(
            title = {
                Text(
                    stringResource(
                        R.string.auto_start_on_bluetooth,
                    ),
                )
            },
            description =
                stringResource(
                    R.string.auto_start_on_bluetooth_desc,
                ),
            icon = {
                Icon(
                    painterResource(R.drawable.bluetooth),
                    null,
                )
            },
            checked = autoStartOnBluetooth,
            onCheckedChange = onAutoStartOnBluetoothChange,
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.queue),
        )

        SwitchPreference(
            title = {
                Text(stringResource(R.string.persistent_queue))
            },
            description =
                stringResource(R.string.persistent_queue_desc),
            icon = {
                Icon(
                    painterResource(R.drawable.queue_music),
                    null,
                )
            },
            checked = persistentQueue,
            onCheckedChange = onPersistentQueueChange,
        )

        SwitchPreference(
            title = {
                Text(stringResource(R.string.permanent_shuffle))
            },
            description =
                stringResource(R.string.permanent_shuffle_desc),
            icon = {
                Icon(
                    painterResource(R.drawable.shuffle),
                    null,
                )
            },
            checked = permanentShuffle,
            onCheckedChange = onPermanentShuffleChange,
        )

        SwitchPreference(
            title = {
                Text(stringResource(R.string.auto_download_on_like))
            },
            description =
                stringResource(
                    R.string.auto_download_on_like_desc,
                ),
            icon = {
                Icon(
                    painterResource(R.drawable.download),
                    null,
                )
            },
            checked = autoDownloadOnLike,
            onCheckedChange = onAutoDownloadOnLikeChange,
        )

        SwitchPreference(
            title = {
                Text(
                    stringResource(
                        R.string.auto_skip_next_on_error,
                    ),
                )
            },
            description =
                stringResource(
                    R.string.auto_skip_next_on_error_desc,
                ),
            icon = {
                Icon(
                    painterResource(R.drawable.skip_next),
                    null,
                )
            },
            checked = autoSkipNextOnError,
            onCheckedChange = onAutoSkipNextOnErrorChange,
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.misc),
        )

        SwitchPreference(
            title = {
                Text(
                    stringResource(
                        R.string.stop_music_on_task_clear,
                    ),
                )
            },
            icon = {
                Icon(
                    painterResource(R.drawable.clear_all),
                    null,
                )
            },
            checked = stopMusicOnTaskClear,
            onCheckedChange = onStopMusicOnTaskClearChange,
        )

        PreferenceEntry(
            title = {
                Text(stringResource(R.string.artist_separators))
            },
            description =
                artistSeparators
                    .map { "\"$it\"" }
                    .joinToString("  "),
            icon = {
                Icon(
                    painterResource(R.drawable.artist),
                    null,
                )
            },
            onClick = {
                showArtistSeparatorsDialog = true
            },
        )

        PreferenceEntry(
            title = {
                Text(
                    stringResource(
                        R.string.manage_playlist_tags,
                    ),
                )
            },
            description =
                stringResource(
                    R.string.manage_playlist_tags_desc,
                ),
            icon = {
                Icon(
                    painterResource(R.drawable.style),
                    null,
                )
            },
            onClick = {
                showTagsManagementDialog = true
            },
        )
    }

    TopAppBar(
        title = {
            Text(stringResource(R.string.player_and_audio))
        },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
