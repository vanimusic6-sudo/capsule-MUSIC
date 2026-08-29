
/* * capsule fork
 * Sources settings screen.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioQualityKey
import com.nikhil.yt.playback.source.AudioSource
import com.nikhil.yt.playback.source.AudioSourceKey
import com.nikhil.yt.playback.source.AudioSourceManager
import com.nikhil.yt.playback.source.DeezerAudioProvider
import com.nikhil.yt.playback.source.DeezerAudioQuality
import com.nikhil.yt.playback.source.DeezerAudioQualityKey
import com.nikhil.yt.playback.source.DeezerFastModeKey
import com.nikhil.yt.utils.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSettings(navController: NavController) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()

    val selectedName by
        remember(context) {
            context.dataStore.data.map { prefs ->
                prefs[AudioSourceKey] ?: AudioSource.YOUTUBE.name
            }
        }.collectAsState(initial = AudioSource.YOUTUBE.name)
    val selectedSource = AudioSource.fromPreference(selectedName)

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

    val deezerFastMode by
        remember(context) {
            context.dataStore.data.map { prefs -> prefs[DeezerFastModeKey] ?: true }
        }.collectAsState(initial = true)

    var testing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<AudioSourceManager.HealthReport?>(null) }

    fun refreshYoutube() {
        playerConnection?.service?.refreshCurrentAudioSource()
    }

    fun applyPreferredSource() {
        playerConnection?.service?.applyPreferredAudioSource(force = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = "Back",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Playback source",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "YouTube stays the live fallback while Deezer is checked in the background. Downloads always keep the original YouTube ID.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
            }

            item {
                SourceOption(
                    title = "YouTube",
                    selected = selectedSource == AudioSource.YOUTUBE,
                    subtitle = "Stable · existing Capsule playback core",
                    enabled = true,
                    onClick = {
                        scope.launch {
                            context.dataStore.edit { it[AudioSourceKey] = AudioSource.YOUTUBE.name }
                            AudioSourceManager.onPreferredSourceChanged(AudioSource.YOUTUBE)
                            applyPreferredSource()
                        }
                    },
                )
            }

            item {
                SourceOption(
                    title = "Deezer",
                    selected = selectedSource == AudioSource.DEEZER,
                    subtitle = "Experimental · background check · YouTube never waits",
                    enabled = true,
                    onClick = {
                        scope.launch {
                            context.dataStore.edit { it[AudioSourceKey] = AudioSource.DEEZER.name }
                            AudioSourceManager.onPreferredSourceChanged(AudioSource.DEEZER)
                            applyPreferredSource()
                        }
                    },
                )
            }

            item {
                SourceOption(
                    title = "Amazon Music",
                    selected = selectedSource == AudioSource.AMAZON_MUSIC,
                    subtitle = "Access test available · playback backend not enabled yet",
                    enabled = false,
                    onClick = {},
                )
            }

            item {
                Text(
                    text = "Quality / bitrate",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (selectedSource == AudioSource.YOUTUBE) {
                item {
                    ChoiceCard(
                        title = "Auto",
                        subtitle = "Automatic format selection; actual bitrate is shown in the player badge",
                        selected = youtubeQuality == AudioQuality.AUTO,
                    ) {
                        scope.launch {
                            context.dataStore.edit { it[AudioQualityKey] = AudioQuality.AUTO.name }
                            refreshYoutube()
                        }
                    }
                }
                item {
                    ChoiceCard(
                        title = "Low",
                        subtitle = "Lower data usage",
                        selected = youtubeQuality == AudioQuality.LOW,
                    ) {
                        scope.launch {
                            context.dataStore.edit { it[AudioQualityKey] = AudioQuality.LOW.name }
                            refreshYoutube()
                        }
                    }
                }
                item {
                    ChoiceCard(
                        title = "High",
                        subtitle = "High quality YouTube format",
                        selected = youtubeQuality == AudioQuality.HIGH,
                    ) {
                        scope.launch {
                            context.dataStore.edit { it[AudioQualityKey] = AudioQuality.HIGH.name }
                            refreshYoutube()
                        }
                    }
                }
                item {
                    ChoiceCard(
                        title = "Highest",
                        subtitle = "Best available YouTube format",
                        selected = youtubeQuality == AudioQuality.HIGHEST,
                    ) {
                        scope.launch {
                            context.dataStore.edit { it[AudioQualityKey] = AudioQuality.HIGHEST.name }
                            refreshYoutube()
                        }
                    }
                }
            } else if (selectedSource == AudioSource.DEEZER) {
                DeezerAudioQuality.values().forEach { quality ->
                    item {
                        ChoiceCard(
                            title = quality.title,
                            subtitle =
                                when (quality) {
                                    DeezerAudioQuality.MP3_128 -> "Fastest / lowest traffic"
                                    DeezerAudioQuality.MP3_320 -> "Recommended Deezer quality"
                                    DeezerAudioQuality.FLAC -> "Lossless when the route provides it"
                                },
                            selected = deezerQuality == quality,
                        ) {
                            scope.launch {
                                context.dataStore.edit { it[DeezerAudioQualityKey] = quality.name }
                                applyPreferredSource()
                            }
                        }
                    }
                }
            } else {
                item {
                    InfoCard(
                        title = "Amazon Music quality",
                        body = "Quality controls will appear when the Amazon playback backend is connected.",
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        context.dataStore.edit { it[DeezerFastModeKey] = !deezerFastMode }
                                        if (selectedSource == AudioSource.DEEZER) applyPreferredSource()
                                    }
                                }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Deezer Fast Mode", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Short search and resolver path to avoid player delays",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = deezerFastMode,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    context.dataStore.edit { it[DeezerFastModeKey] = enabled }
                                    if (selectedSource == AudioSource.DEEZER) applyPreferredSource()
                                }
                            },
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        if (testing) return@Button
                        testing = true
                        report = null
                        scope.launch {
                            report = AudioSourceManager.testSources(context)
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("  Testing access and speed…")
                    } else {
                        Text("Test speed and access")
                    }
                }
            }

            report?.let { result ->
                item {
                    Text(
                        text = "Test results",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item { HealthCard(result.youtube) }
                item { HealthCard(result.deezerApi) }
                item { HealthCard(result.deezerPreview) }
                item { HealthCard(result.deezerResolver) }
                item { HealthCard(result.amazonWeb) }
                item {
                    InfoCard(
                        title = "Deezer full stream",
                        body =
                            when (result.deezerFullStream) {
                                DeezerAudioProvider.FullStreamState.DIRECT ->
                                    "Direct full stream available — Capsule can switch to Deezer without touching the YouTube resolver."
                                DeezerAudioProvider.FullStreamState.PROTECTED ->
                                    "Resolver is reachable, but the full stream is protected. Capsule keeps the already-playing YouTube stream instead of stalling."
                                DeezerAudioProvider.FullStreamState.UNAVAILABLE ->
                                    "No usable full stream was returned. YouTube keeps playing normally."
                            },
                    )
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun SourceOption(
    title: String,
    selected: Boolean,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color =
                        if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.55f,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HealthCard(health: AudioSourceManager.EndpointHealth) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    health.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    health.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                text =
                    if (health.available) {
                        health.latencyMs?.let { "${it} ms" } ?: "OK"
                    } else {
                        "Unavailable"
                    },
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (health.available) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
