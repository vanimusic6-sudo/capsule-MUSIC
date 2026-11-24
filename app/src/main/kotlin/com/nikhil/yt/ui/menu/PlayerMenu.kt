/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.menu
import com.nikhil.yt.ui.utils.rememberCapsuleFlingBehavior

import com.nikhil.yt.playback.canPrioritize
import com.nikhil.yt.playback.visibleState

import com.nikhil.yt.ui.player.CapsuleSleepTimerDialog
import com.nikhil.yt.together.TogetherRole
import com.nikhil.yt.together.TogetherSessionState
import com.nikhil.yt.ui.component.ArtistSelectionItem
import com.nikhil.yt.ui.component.VeluneLoader
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.nikhil.yt.ui.component.CapsuleMenuListItem as ListItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import android.widget.Toast
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.LocalDownloadUtil
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.ArtistSeparatorsKey
import com.nikhil.yt.constants.EqualizerBandLevelsMbKey
import com.nikhil.yt.constants.EqualizerCurveIntensityPercentKey
import com.nikhil.yt.constants.EqualizerBassBoostEnabledKey
import com.nikhil.yt.constants.EqualizerBassBoostStrengthKey
import com.nikhil.yt.constants.EqualizerCustomProfilesJsonKey
import com.nikhil.yt.constants.EqualizerEnabledKey
import com.nikhil.yt.constants.SoundStudioSpeedKey
import com.nikhil.yt.constants.SoundStudioPitchKey
import com.nikhil.yt.constants.SoundStudioTempoVariantKey
import com.nikhil.yt.constants.EqualizerOutputGainEnabledKey
import com.nikhil.yt.constants.EqualizerOutputGainMbKey
import com.nikhil.yt.constants.EqualizerSelectedProfileIdKey
import com.nikhil.yt.constants.ListItemHeight
import com.nikhil.yt.discovery.ExperimentalArtistRoute
import com.nikhil.yt.discovery.ExperimentalCatalog
import com.nikhil.yt.discovery.ExperimentalDownload
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.models.sanitizedArtistCredits
import com.nikhil.yt.playback.EqCapabilities
import com.nikhil.yt.playback.EqSettings
import com.nikhil.yt.playback.SoundStudioTempoVariant
import com.nikhil.yt.playback.CapsuleEqEngineMode
import com.nikhil.yt.playback.protectedEqAudioFactor
import com.nikhil.yt.playback.capsuleSafeEqInputGainMb
import com.nikhil.yt.playback.capsuleUnifiedEqLevels
import com.nikhil.yt.playback.capsuleEqCurveSpreadMb
import com.nikhil.yt.playback.CAPSULE_MAX_BASS_STRENGTH
import com.nikhil.yt.playback.CAPSULE_MAX_VIRTUALIZER_STRENGTH
import com.nikhil.yt.playback.CAPSULE_MAX_OUTPUT_GAIN_MB
import com.nikhil.yt.playback.CAPSULE_MAX_EQ_BOOST_MB
import com.nikhil.yt.playback.CAPSULE_MAX_LOW_BASS_BOOST_MB
import com.nikhil.yt.playback.CapsuleEqPresets
import com.nikhil.yt.playback.CAPSULE_STUDIO_CAPABILITIES
import com.nikhil.yt.playback.scaleEqBandLevels
import com.nikhil.yt.playback.inverseScaledEqLevel
import com.nikhil.yt.playback.EqProfile
import com.nikhil.yt.playback.EqProfilesPayload
import com.nikhil.yt.playback.EqualizerJson
import com.nikhil.yt.playback.ExoDownloadService
import com.nikhil.yt.playback.queues.YouTubeQueue
import com.nikhil.yt.ui.component.BottomSheetState
import com.nikhil.yt.ui.component.ListDialog
import com.nikhil.yt.ui.component.MenuSurfaceSection
import com.nikhil.yt.ui.component.CapsuleMenuMediaHeader
import com.nikhil.yt.ui.component.NewAction
import com.nikhil.yt.ui.component.NewActionGrid
import com.nikhil.yt.ui.player.CapsuleLyricsVolumeSlider
import com.nikhil.yt.ui.player.CapsuleLyricsVolumeIcon
import com.nikhil.yt.ui.component.TextFieldDialog
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import java.util.UUID

@Composable
fun PlayerMenu(
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    playerBottomSheetState: BottomSheetState,
    isQueueTrigger: Boolean? = false,
    onShowDetailsDialog: () -> Unit,
    onDismiss: () -> Unit,
) {
    mediaMetadata ?: return
    val context = LocalContext.current
    val resources = LocalResources.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val activityResultLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val librarySong by database.song(mediaMetadata.id).collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()

    val downloadUtil = LocalDownloadUtil.current
    val download by downloadUtil.getDownload(mediaMetadata.id)
        .collectAsState(initial = null)
    val isExternalCatalog = mediaMetadata.id.startsWith("capsule-open:")
    val isOfflineAudio = mediaMetadata.id.startsWith("capsule-open:offline:")
    val isRadio = mediaMetadata.id.startsWith("capsule-open:radio:")
    val artists =
        remember(mediaMetadata.artists) {
            mediaMetadata.artists.sanitizedArtistCredits()
        }

    // Artist separators for splitting artist names
    val (artistSeparators) = rememberPreference(ArtistSeparatorsKey, defaultValue = ",;/&")

    // Split artists by configured separators
    data class SplitArtist(
        val name: String,
        val originalArtist: MediaMetadata.Artist?
    )

    val splitArtists = remember(artists, artistSeparators) {
        if (artistSeparators.isEmpty()) {
            artists.map { SplitArtist(it.name, it) }
        } else {
            val separatorRegex = "[${Regex.escape(artistSeparators)}]".toRegex()
            artists.flatMap { artist ->
                val parts = artist.name.split(separatorRegex).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size > 1) {
                    parts.mapIndexed { index, name ->
                        SplitArtist(name, if (index == 0) artist else null)
                    }
                } else {
                    listOf(SplitArtist(artist.name, artist))
                }
            }
        }
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        allowSyncing = !isExternalCatalog,
        onGetSong = { _ ->
            database.withTransaction {
                insert(mediaMetadata) {
                    if (mediaMetadata.id.startsWith("capsule-open:offline:"))
                        it.copy(isLocal = true) else it
                }
            }
            // The dialog itself syncs YouTube playlists where allowed.
            // Independent entries may only enter Capsule's local playlists.
            listOf(mediaMetadata.id)
        },
        onDismiss = {
            showChoosePlaylistDialog = false
        },
        onAddComplete = { songCount, playlistNames ->
            val message = when {
                playlistNames.size == 1 -> resources.getString(R.string.added_to_playlist, playlistNames.first())
                else -> resources.getString(R.string.added_to_n_playlists, playlistNames.size)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        },
    )

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(onDismiss = { showSelectArtistDialog = false }) {
            items(splitArtists.distinctBy { it.name }) { splitArtist ->
                ArtistSelectionItem(
                    name = splitArtist.name,
                    artistId = splitArtist.originalArtist?.id,
                    thumbnailUrl = splitArtist.originalArtist?.thumbnailUrl,
                    onClick = {
                        val id = splitArtist.originalArtist?.id ?: return@ArtistSelectionItem
                        navController.navigate(
                            ExperimentalArtistRoute.forMetadata(mediaMetadata) ?: "artist/$id"
                        )
                        showSelectArtistDialog = false
                        playerBottomSheetState.collapseSoft()
                        onDismiss()
                    },
                )
            }
        }
    }

    var showEqualizerDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showEqualizerDialog) {
        EqualizerDialog(
            onDismiss = { showEqualizerDialog = false },
            openSystemEqualizer = {
                val intent =
                    Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(
                            AudioEffect.EXTRA_AUDIO_SESSION,
                            playerConnection.player.audioSessionId,
                        )
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                if (intent.resolveActivity(context.packageManager) != null) {
                    activityResultLauncher.launch(intent)
                }
            },
        )
    }

    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    var sleepMinutes by rememberSaveable { mutableFloatStateOf(30f) }
    val togetherState by playerConnection.service.togetherSessionState.collectAsState()
    val canSetSleepTimer = (togetherState as? TogetherSessionState.Joined)?.role !is TogetherRole.Guest
    if (showSleepTimer) {
        CapsuleSleepTimerDialog(
            minutes = sleepMinutes,
            enabled = canSetSleepTimer,
            active = playerConnection.service.sleepTimer.isActive,
            onMinutesChange = { sleepMinutes = it },
            onEndOfSong = {
                if (canSetSleepTimer) playerConnection.service.sleepTimer.start(-1)
                showSleepTimer = false
            },
            onClear = {
                if (canSetSleepTimer) playerConnection.service.sleepTimer.clear()
                showSleepTimer = false
            },
            onConfirm = {
                if (canSetSleepTimer) playerConnection.service.sleepTimer.start(sleepMinutes.toInt())
                showSleepTimer = false
            },
            onDismiss = { showSleepTimer = false },
        )
    }

    val noTitle = stringResource(R.string.no_title)
    val nowPlayingTitle = mediaMetadata.title.ifBlank { noTitle }

    val nowPlayingSubtitle =
        remember(mediaMetadata.artists) {
            artists.joinToString(separator = ", ") { it.name }
        }

    CapsuleMenuMediaHeader(
        title = nowPlayingTitle,
        subtitle = nowPlayingSubtitle,
        overline = stringResource(R.string.now_playing),
        thumbnailUrl = mediaMetadata.thumbnailUrl,
    )

    if (isQueueTrigger != true) {
        Spacer(modifier = Modifier.height(8.dp))

        PlayerVolumeCard(playerConnection = playerConnection)
        Spacer(modifier = Modifier.height(16.dp))
    }

    LazyColumn(
            flingBehavior = rememberCapsuleFlingBehavior(),
            overscrollEffect = null,
        state = androidx.compose.foundation.lazy.rememberLazyListState(),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        // The scrollable content begins immediately after the VOLUME card.
        item(key = "quick-actions", contentType = "menu-section") {
            // One measured root per LazyColumn item: no phantom gaps or
            // independently measured spacer nodes during fast flings.
            Column(modifier = Modifier.fillMaxWidth()) {
            // Quick actions are the FIRST lazy item after volume, so scrolls
            // begin under the slider instead of after Capsule Together.
            // Priority actions: exactly two small, outlined playlist / copy tiles.
            NewActionGrid(
                actions = listOf(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.capsule_soft_playlist_add),
                                contentDescription = null, modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = stringResource(R.string.add_to_playlist),
                        onClick = { showChoosePlaylistDialog = true },
                     ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.capsule_soft_link),
                                contentDescription = null, modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = stringResource(R.string.copy_link),
                        onClick = {
                            val url = if (isExternalCatalog) {
                                mediaMetadata.sourceUrl?.takeIf { it.startsWith("https://") }
                            } else {
                                "https://music.youtube.com/watch?v=${mediaMetadata.id}"
                            }
                            if (!url.isNullOrBlank()) {
                                val clipboard = context.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE,
                                ) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                    resources.getString(R.string.copy_link), url,
                                ))
                                android.widget.Toast.makeText(
                                    context, R.string.link_copied, android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                onDismiss()
                            }
                        },
                    ),
                ),
                compact = true,
            )
    
            Spacer(modifier = Modifier.height(8.dp))
            }
        }
        if ((!isRadio && !isOfflineAudio && splitArtists.isNotEmpty()) || mediaMetadata.album != null) {
            item(key = "artists-album", contentType = "menu-section") {
                MenuSurfaceSection() {
                    Column {
                        if (!isRadio && !isOfflineAudio && splitArtists.isNotEmpty()) {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.view_artist)) },
                                supportingContent = {
                                    Text(
                                        text = artists.joinToString(separator = ", ") { it.name },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.capsule_soft_artist),
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        if (splitArtists.size == 1 && splitArtists[0].originalArtist != null) {
                                            onDismiss()
                                            playerBottomSheetState.snapTo(playerBottomSheetState.collapsedBound)
                                            navController.navigate(
                                                ExperimentalArtistRoute.forMetadata(mediaMetadata)
                                                    ?: "artist/${splitArtists[0].originalArtist!!.id}"
                                            )
                                        } else {
                                            showSelectArtistDialog = true
                                        }
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }

if (mediaMetadata.album != null) {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.view_album)) },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.capsule_soft_album),
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        onDismiss()
                                        playerBottomSheetState.snapTo(playerBottomSheetState.collapsedBound)
                                        navController.navigate("album/${mediaMetadata.album.id}")
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
        }
        // Keep audio tools after the artist/album tiles, never as giant squares.
        if (isQueueTrigger != true) {
            item(key = "audio-tools", contentType = "menu-section") {
                MenuSurfaceSection {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.equalizer)) },
                        leadingContent = {
                            Icon(painterResource(R.drawable.graphic_eq), contentDescription = null)
                        },
                        modifier = Modifier.clickable { showEqualizerDialog = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        // Sleep timer is available even when More was opened from the queue.
        item(key = "sleep-timer", contentType = "menu-section") {
            MenuSurfaceSection {
                    // Preserve the existing timer functionality without displacing Details.
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.sleep_timer)) },
                        leadingContent = {
                            Icon(painterResource(R.drawable.capsule_soft_bedtime), contentDescription = null)
                        },
                        modifier = Modifier.clickable(enabled = canSetSleepTimer) {
                            showSleepTimer = true
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
            }
        }
        if (!isExternalCatalog || isOfflineAudio) item(key = "download", contentType = "menu-section") {
            if (isOfflineAudio) {
                MenuSurfaceSection() {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.remove_download),
                                color = MaterialTheme.colorScheme.error)
                        },
                        leadingContent = {
                            Icon(painterResource(R.drawable.capsule_soft_download), contentDescription = null,
                                tint = MaterialTheme.colorScheme.error)
                        },
                        modifier = Modifier.clickable {
                            coroutineScope.launch {
                                val match = ExperimentalDownload.savedFiles(context)
                                    .firstOrNull {
                                        mediaMetadata.id ==
                                            com.nikhil.yt.discovery.OfflineAudioQueue.PREFIX + it.id
                                    }
                                val removed = match != null &&
                                    ExperimentalDownload.deleteSaved(context, match)
                                if (removed) {
                                    database.withTransaction {
                                        getSongById(mediaMetadata.id)?.song?.let { delete(it) }
                                    }
                                    mediaMetadata.id.removePrefix(
                                        com.nikhil.yt.discovery.OfflineAudioQueue.PREFIX
                                    ).toLongOrNull()?.let { id ->
                                        com.nikhil.yt.discovery.OfflineAudioQueue
                                            .removeDeletedFromPlayer(playerConnection.player, id)
                                    }
                                    // Keep More open: the row updates from the saved-files state.
                                } else {
                                    Toast.makeText(context, R.string.capsule_download_remove_failed,
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        highlightBorder = true,
                        highlightBorderColor = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                MenuSurfaceSection() {
                    YouTubeDownloadMenuItems(
                        download = download, mediaId = mediaMetadata.id, onDismiss = onDismiss,
                        trackTitle = mediaMetadata.title,
                        keepOpenOnDownload = true, showActiveStopAction = false,
                        instantRemove = true,
                        onStart = {
                            database.transaction { insert(mediaMetadata) }
                            val request = DownloadRequest.Builder(mediaMetadata.id, mediaMetadata.id.toUri())
                                .setCustomCacheKey(mediaMetadata.id)
                                .setData(mediaMetadata.title.toByteArray()).build()
                            com.nikhil.yt.playback.enqueuePriorityAudioDownload(
                                context, ExoDownloadService::class.java, request, false,
                            )
                        },
                    )
                }
            }
        }
        if (!isExternalCatalog) {
            item(key = "radio-action", contentType = "menu-section") {
                MenuSurfaceSection {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.start_radio)) },
                        leadingContent = {
                            Icon(painterResource(R.drawable.radio), contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            Toast.makeText(
                                context, resources.getString(R.string.starting_radio),
                                Toast.LENGTH_SHORT,
                            ).show()
                            playerConnection.startRadioSeamlessly()
                            onDismiss()
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        item(key = "tools", contentType = "menu-section") {
            MenuSurfaceSection() {
                Column {
                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.details)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.capsule_soft_info),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                onShowDetailsDialog()
                                onDismiss()
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )

                }
            }
        }
    }
}

@Composable
private fun PlayerVolumeCard(
    playerConnection: com.nikhil.yt.playback.PlayerConnection,
    modifier: Modifier = Modifier,
) {
    // A volume change used to invalidate the whole PlayerMenu, recreating
    // its action grid while the LazyColumn was actively scrolling. Subscribe
    // only inside this small leaf so scrolling no longer pays that cost.
    val volume by playerConnection.service.playerVolume.collectAsState()
    val onVolumeChange: (Float) -> Unit = remember(playerConnection) {
        { value -> playerConnection.service.playerVolume.value = value }
    }
    val safeVolume = volume.coerceIn(0f, 1f)

    Surface(
        shape = com.nikhil.yt.ui.component.CapsuleMenuTileShape,
        color = com.nikhil.yt.ui.component.CapsuleMenuBlack,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            // A taller, full-width backing aligned with every other menu tile.
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = 88.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.volume),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = "${(safeVolume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Same tactile graphite slider AND endpoint icons as the
            // fullscreen Lyrics player. No second Material Slider implementation.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CapsuleLyricsVolumeIcon(
                    muted = true,
                    onClick = { onVolumeChange(0f) },
                )
                CapsuleLyricsVolumeSlider(
                    value = safeVolume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                )
                CapsuleLyricsVolumeIcon(
                    muted = false,
                    onClick = { onVolumeChange(1f) },
                )
            }

        }
    }
}

private const val TempoMin = 0.25f
private const val TempoMax = 2f
private const val PitchMin = 0.25f
private const val PitchMax = 2f

private fun Float.safeCoerceIn(min: Float, max: Float, fallback: Float): Float {
    val safe = if (this.isFinite()) this else fallback
    return safe.coerceIn(min, max)
}

private fun Float.quantize(step: Float): Float {
    if (step <= 0f) return this
    return (round(this / step) * step).coerceAtLeast(0f)
}

private fun formatMultiplier(multiplier: Float): String {
    return String.format("%.2f", multiplier)
}

private fun sliderToMultiplier(slider: Float): Float {
    val t = slider.coerceIn(0f, 1f)
    val y = (t - 0.5f) * 2f
    val curve = 2.2f
    val absY = abs(y).pow(curve)
    val shaped = when {
        y > 0f -> absY
        y < 0f -> -absY
        else -> 0f
    }
    val exponent = if (y < 0f) 2f * shaped else shaped
    return 2f.pow(exponent).coerceIn(TempoMin, TempoMax)
}

private fun multiplierToSlider(multiplier: Float): Float {
    val m = multiplier.coerceIn(TempoMin, TempoMax)
    val log = log2(m)
    val curve = 2.2f
    val shaped = if (m < 1f) (log / 2f) else log
    val absShaped = abs(shaped).pow(1f / curve)
    val y = when {
        shaped > 0f -> absShaped
        shaped < 0f -> -absShaped
        else -> 0f
    }
    return (0.5f + y / 2f).coerceIn(0f, 1f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerDialog(
    onDismiss: () -> Unit,
    openSystemEqualizer: () -> Unit,
) {
    val service = LocalPlayerConnection.current?.service ?: return
    val engine by service.eqEngineMode.collectAsState()
    val (eqEnabled, _) = rememberPreference(EqualizerEnabledKey, defaultValue = false)
    val (selectedId, _) = rememberPreference(EqualizerSelectedProfileIdKey, defaultValue = "flat")
    val (storedRaw, _) = rememberPreference(EqualizerBandLevelsMbKey, defaultValue = "")
    val (tempo, _) = rememberPreference(SoundStudioTempoVariantKey, defaultValue = "")

    // Ten *real* studio bands on Android DynamicsProcessing. On OEM fallback
    // these ten control points are mapped by Hz onto its available native bands.
    val studio = CAPSULE_STUDIO_CAPABILITIES
    val bandCount = studio.bandCount
    val stored = remember(storedRaw) {
        com.nikhil.yt.playback.resampleEqLevelsByIndex(
            decodeBandLevelsMb(storedRaw), bandCount,
        )
    }
    val preset = selectedId.removePrefix("capsule:")
        .let(CapsuleEqPresets::byId)
        ?.takeIf { eqEnabled }
    val manual = eqEnabled && selectedId == "adaptive:manual"
    val initial = when {
        preset != null -> CapsuleEqPresets.levelsFor(
            preset, studio.centerFreqHz, studio.minBandLevelMb, studio.maxBandLevelMb,
        )
        manual -> stored
        else -> List(bandCount) { 0 }
    }
    var draft by remember { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(eqEnabled, selectedId, storedRaw) { draft = null }
    val levels = draft ?: initial
    var selectedBand by rememberSaveable { mutableIntStateOf(3) }
    val ceilings = studio.centerFreqHz.map { hz ->
        when {
            hz <= 120 -> CAPSULE_MAX_LOW_BASS_BOOST_MB
            hz > 8000 -> 200
            hz > 4500 -> 260
            else -> CAPSULE_MAX_EQ_BOOST_MB
        }
    }
    val displayed = levels.mapIndexed { i, gain ->
        gain.coerceIn(-460, ceilings[i])
    }
    val settings = EqSettings(
        enabled = eqEnabled, bandLevelsMb = levels,
        outputGainEnabled = false, outputGainMb = 0,
        bassBoostEnabled = false, bassBoostStrength = 0,
        virtualizerEnabled = false, virtualizerStrength = 0,
        musicalBalanceEnabled = true,
    )
    val applied = capsuleUnifiedEqLevels(settings, studio)

    fun chooseTone(id: String) {
        draft = null
        service.selectSoundVersion(id)
    }
    val activeTempo = SoundStudioTempoVariant.resolve(tempo.takeIf { it.isNotEmpty() }, selectedId)
    val equalizerLabel = stringResource(R.string.equalizer)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.eq_audio_studio)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painterResource(R.drawable.close),
                                contentDescription = stringResource(R.string.dismiss),
                            )
                        }
                    },
                    actions = {
                        Switch(
                            checked = eqEnabled,
                            onCheckedChange = { enabled ->
                                draft = null
                                service.setSoundStudioEqEnabled(enabled)
                            },
                            modifier = Modifier.semantics {
                                contentDescription = equalizerLabel
                            },
                            thumbContent = {
                                Icon(
                                    painterResource(if (eqEnabled) R.drawable.check else R.drawable.close),
                                    contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                Column(
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState(),
                            flingBehavior = rememberCapsuleFlingBehavior(),
                            overscrollEffect = null)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    EqSection(
                        title = stringResource(R.string.eq_ten_band_title),
                        trailing = {
                            TextButton(onClick = {
                                if (eqEnabled) {
                                    draft = null
                                    service.selectSoundVersion(
                                        "adaptive:manual", List(bandCount) { 0 },
                                    )
                                }
                            }, enabled = eqEnabled) {
                                Text(stringResource(R.string.reset))
                            }
                        },
                    ) {
                        CapsuleEqualizerGraph(
                            frequenciesHz = studio.centerFreqHz,
                            levelsMb = displayed,
                            minMb = -460,
                            maxMb = CAPSULE_MAX_EQ_BOOST_MB,
                            bandMaxLevelsMb = ceilings,
                            appliedLevelsMb = if (eqEnabled) applied else emptyList(),
                            selectedBand = selectedBand.coerceIn(0, bandCount - 1),
                            onBandSelected = { selectedBand = it },
                            onLevelChanged = { band, gain ->
                                if (eqEnabled && band in 0 until bandCount) {
                                    draft = levels.toMutableList().apply {
                                        this[band] = gain.coerceIn(-460, ceilings[band])
                                    }
                                }
                            },
                            onFinished = { band, gain ->
                                if (eqEnabled && band in 0 until bandCount) {
                                    val next = levels.toMutableList().apply {
                                        this[band] = gain.coerceIn(-460, ceilings[band])
                                    }
                                    draft = next
                                    service.selectSoundVersion("adaptive:manual", next)
                                }
                            },
                            graphHeight = when {
                                LocalConfiguration.current.screenHeightDp < 690 -> 175.dp
                                else -> 222.dp
                            },
                            editable = eqEnabled,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                com.nikhil.yt.ui.menu.capsuleGraphHz(
                                    studio.centerFreqHz[selectedBand.coerceIn(0, bandCount - 1)],
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                formatDb(applied[selectedBand.coerceIn(0, bandCount - 1)] / 100f),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    EqSection(title = stringResource(R.string.eq_profiles)) {
                        Spacer(Modifier.height(10.dp))
                        CapsuleEqPresets.all.chunked(2).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                row.forEach { version ->
                                    val selected = preset?.id == version.id
                                    Surface(
                                        onClick = {
                                            draft = null
                                            chooseTone("capsule:${version.id}")
                                        },
                                        modifier = Modifier.weight(1f).heightIn(min = 58.dp),
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 13.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = version.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (selected) {
                                                Spacer(Modifier.width(6.dp))
                                                Icon(
                                                    painterResource(R.drawable.check),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            listOf(
                                "flat" to stringResource(R.string.eq_version_original),
                                "adaptive:manual" to stringResource(R.string.eq_adaptive_activate),
                            ).forEach { (id, label) ->
                                val selected = if (id == "flat") !eqEnabled else manual
                                Surface(
                                    onClick = {
                                        if (id == "flat") chooseTone("flat")
                                        else service.selectSoundVersion("adaptive:manual", levels)
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(label, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    EqSection(title = stringResource(R.string.eq_tempo_versions)) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            listOf(
                                SoundStudioTempoVariant.NORMAL to stringResource(R.string.eq_tempo_normal),
                                SoundStudioTempoVariant.SPEED_UP to "Speed Up",
                                SoundStudioTempoVariant.SLOWED to "Slowed",
                            ).forEach { (variant, label) ->
                                val selected = activeTempo == variant
                                Surface(
                                    onClick = { service.selectTempoVariant(variant) },
                                    modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (eqEnabled && engine == CapsuleEqEngineMode.NATIVE_FALLBACK) {
                        Text(
                            stringResource(R.string.eq_ten_band_fallback),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    } else if (eqEnabled && engine == CapsuleEqEngineMode.UNAVAILABLE) {
                        Text(
                            stringResource(R.string.eq_engine_unavailable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun CapsuleStudioSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = SliderDefaults.colors(
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
        thumbColor = MaterialTheme.colorScheme.primary,
        disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    )
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        thumb = { _ ->
            Box(
                Modifier.size(width = 12.dp, height = 22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                modifier = Modifier.height(7.dp),
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

/** Compact effect controls share one surface instead of stacking nested cards. */
@Composable
private fun CapsuleEffectRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    value: Int,
    valueRange: IntRange,
    valueLabel: String,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall)
            // Keep all rows the same height when effects turn on/off.
            // Conditional content caused the entire scroll position to jump.
            Text(valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
        CapsuleStudioSlider(
                value = value.toFloat().coerceIn(
                    valueRange.first.toFloat(), valueRange.last.toFloat(),
                ),
                onValueChange = { next ->
                    onValueChange(next.toInt().coerceIn(valueRange.first, valueRange.last))
                },
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                enabled = enabled && checked,
                modifier = Modifier.fillMaxWidth(),
            )
    }
}

@Composable
private fun EqSection(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        color = com.nikhil.yt.ui.component.CapsuleMenuBlack,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

private fun decodeBandLevelsMb(raw: String?): List<Int> {
    if (raw.isNullOrBlank()) return emptyList()
    return com.nikhil.yt.playback.decodeEqBandLevels(raw)
}

private fun encodeBandLevelsMb(levelsMb: List<Int>): String {
    return runCatching { EqualizerJson.json.encodeToString(levelsMb) }.getOrNull().orEmpty()
}

private fun decodeProfilesPayload(raw: String?): EqProfilesPayload {
    if (raw.isNullOrBlank()) return EqProfilesPayload()
    if (raw.length > 65536) return EqProfilesPayload()
    val parsed = runCatching { EqualizerJson.json.decodeFromString<EqProfilesPayload>(raw) }
        .getOrNull() ?: return EqProfilesPayload()
    return parsed.copy(profiles = parsed.profiles.take(24).map { profile ->
        profile.copy(
            id = profile.id.take(80),
            name = profile.name.take(80),
            bandCenterFreqHz = profile.bandCenterFreqHz.take(64),
            bandLevelsMb = profile.bandLevelsMb.take(64).map { it.coerceIn(-1500, 1500) },
            outputGainMb = profile.outputGainMb.coerceIn(-1500, 0),
            bassBoostStrength = profile.bassBoostStrength.coerceIn(0, CAPSULE_MAX_BASS_STRENGTH),
            virtualizerStrength = 0,
        )
    })
}

private fun encodeProfilesPayload(payload: EqProfilesPayload): String {
    return runCatching { EqualizerJson.json.encodeToString(payload) }.getOrNull().orEmpty()
}

private fun resampleLevelsByIndex(levelsMb: List<Int>, targetCount: Int): List<Int> {
    if (targetCount <= 0) return emptyList()
    if (levelsMb.isEmpty()) return List(targetCount) { 0 }
    if (levelsMb.size == targetCount) return levelsMb
    if (targetCount == 1) return listOf((levelsMb.sumOf { it.toLong() } / levelsMb.size).toInt())

    val lastIndex = levelsMb.lastIndex.toFloat().coerceAtLeast(1f)
    return List(targetCount) { i ->
        val pos = i.toFloat() * lastIndex / (targetCount - 1).toFloat()
        val lo = kotlin.math.floor(pos).toInt().coerceIn(0, levelsMb.lastIndex)
        val hi = kotlin.math.ceil(pos).toInt().coerceIn(0, levelsMb.lastIndex)
        val t = (pos - lo.toFloat()).coerceIn(0f, 1f)
        val a = levelsMb[lo]
        val b = levelsMb[hi]
        (a.toDouble() + (b.toDouble() - a.toDouble()) * t).toInt()
    }
}

private fun formatHz(hz: Int): String {
    if (hz <= 0) return ""
    return if (hz >= 1000) "${(hz / 1000f).let { round(it * 10f) / 10f }}k" else hz.toString()
}

private fun formatDb(db: Float): String {
    val rounded = round(db * 10f) / 10f
    return "${if (rounded > 0f) "+" else ""}$rounded dB"
}
