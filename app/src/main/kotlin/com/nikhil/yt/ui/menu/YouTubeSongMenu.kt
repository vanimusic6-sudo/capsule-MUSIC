/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */


package com.nikhil.yt.ui.menu
import com.nikhil.yt.ui.component.CapsuleMenuListItem
import com.nikhil.yt.ui.utils.rememberCapsuleFlingBehavior

import com.nikhil.yt.playback.canPrioritize
import com.nikhil.yt.playback.visibleState

import com.nikhil.yt.ui.component.CapsuleFavoriteIcon
import com.nikhil.yt.ui.component.CapsuleMenuMediaHeader
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.nikhil.yt.ui.component.StandardChrome
import com.nikhil.yt.ui.component.ArtistSelectionItem
import com.nikhil.yt.ui.component.VeluneLoader
import android.annotation.SuppressLint
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.LocalDownloadUtil
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.LocalSyncUtils
import com.nikhil.yt.R
import com.nikhil.yt.constants.ArtistSeparatorsKey
import com.nikhil.yt.constants.ListThumbnailSize
import com.nikhil.yt.constants.ThumbnailCornerRadius
import com.nikhil.yt.db.entities.SongEntity
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.playback.ExoDownloadService
import com.nikhil.yt.playback.queues.YouTubeQueue
import com.nikhil.yt.ui.component.ListDialog
import com.nikhil.yt.ui.component.LocalBottomSheetPageState
import com.nikhil.yt.ui.component.NewAction
import com.nikhil.yt.ui.component.NewActionGrid
import com.nikhil.yt.ui.utils.ShowMediaInfo
import com.nikhil.yt.utils.joinByBullet
import com.nikhil.yt.utils.makeTimeString
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@SuppressLint("MutableCollectionMutableState")
@Composable
fun YouTubeSongMenu(
    song: SongItem,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val librarySong by database.song(song.id).collectAsState(initial = null)
    val download by LocalDownloadUtil.current.getDownload(song.id).collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    val artists = remember(song.artists) {
        song.artists
            .map { artist ->
                MediaMetadata.Artist(
                    id = artist.id,
                    name = artist.name,
                )
            }
            .distinctBy { artist -> artist.id ?: "name:${artist.name.trim().lowercase()}" }
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
        onGetSong = { playlist ->  
            database.transaction {  
                insert(song.toMediaMetadata())  
            }  
            // AddToPlaylistDialog owns authenticated per-destination writes.
            listOf(song.id)  
        },  
        onDismiss = { showChoosePlaylistDialog = false },
        onAddComplete = { _, playlistNames ->
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
                    onClick = {
                        val id = splitArtist.originalArtist?.id ?: return@ArtistSelectionItem
                        navController.navigate("artist/$id")
                        showSelectArtistDialog = false
                        onDismiss()
                    },
                )
            }
        }
    }  

    CapsuleMenuMediaHeader(
        title = song.title,
        subtitle = joinByBullet(
            song.artists.joinToString { it.name },
            song.duration?.let { makeTimeString(it * 1000L) },
        ),
        thumbnailUrl = song.thumbnail,
        trailingContent = {  
            val favoriteInteraction = remember { MutableInteractionSource() }
            IconButton(
                interactionSource = favoriteInteraction,  
                onClick = {  
                    database.transaction {  
                        librarySong.let { librarySong ->  
                            val s: SongEntity  
                            if (librarySong == null) {  
                                insert(song.toMediaMetadata(), SongEntity::toggleLike)  
                                s = song.toMediaMetadata().toSongEntity().let(SongEntity::toggleLike)  
                            } else {  
                                s = librarySong.song.toggleLike()  
                                update(s)  
                            }  
                            syncUtils.likeSong(s)  
                        }  
                    }  
                },  
            ) {  
                CapsuleFavoriteIcon(
                    liked = librarySong?.song?.liked == true,
                    interactionSource = favoriteInteraction,
                    tint = if (librarySong?.song?.liked == true) StandardChrome.favorite else LocalContentColor.current,
                )  
            }  
        },  
    )  

    Spacer(Modifier.height(8.dp))

    val bottomSheetPageState = LocalBottomSheetPageState.current

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

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            // Row for "Play next", "Add to playlist", and "Share" buttons with grid-like background
            // Enhanced Action Grid using NewMenuComponents
            NewActionGrid(
                actions = listOf(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.capsule_soft_playlist_play),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.play_next),
                        onClick = {
                            playerConnection.playNext(song.toMediaItem())
                            onDismiss()
                        }
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.capsule_soft_playlist_add),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.add_to_playlist),
                        onClick = {
                            showChoosePlaylistDialog = true
                        }
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.capsule_soft_link),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.copy_link),
                        onClick = {
                            val link = song.shareLink
                            if (link.startsWith("https://")) {
                                val clipboard = context.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE,
                                ) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                    resources.getString(R.string.copy_link), link,
                                ))
                                android.widget.Toast.makeText(
                                    context, R.string.link_copied, android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                onDismiss()
                            }
                        }
                    )
                ).let { actions ->
                    listOf(actions[1], actions[2], actions[0])
                },
                modifier = Modifier
            )
        }
        item {
            CapsuleMenuListItem(
                headlineContent = { Text(text = stringResource(R.string.start_radio)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.radio),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                    onDismiss()
                }
            )
        }
        item {
            CapsuleMenuListItem(
                headlineContent = { Text(text = stringResource(R.string.add_to_queue)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.capsule_soft_queue_music),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    playerConnection.addToQueue(song.toMediaItem())
                    onDismiss()
                }
            )
        }
        item {
            CapsuleMenuListItem(
                headlineContent = { 
                    Text(text = if (librarySong?.song?.inLibrary != null) stringResource(R.string.remove_from_library) else stringResource(R.string.add_to_library))
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(if (librarySong?.song?.inLibrary != null) R.drawable.capsule_soft_library_add_check else R.drawable.capsule_soft_library_add),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    if (librarySong?.song?.inLibrary != null) {
                        database.query {
                            inLibrary(song.id, null)
                        }
                    } else {
                        database.transaction {
                            insert(song.toMediaMetadata())
                            inLibrary(song.id, LocalDateTime.now())
                        }
                    }
                }
            )
        }
        item {
            YouTubeDownloadMenuItems(
                download = download, mediaId = song.id, onDismiss = onDismiss,
                        trackTitle = song.title,
                onStart = {
                    database.transaction { insert(song.toMediaMetadata()) }
                    val request = DownloadRequest.Builder(song.id, song.id.toUri())
                        .setCustomCacheKey(song.id)
                        .setData(song.title.toByteArray()).build()
                    com.nikhil.yt.playback.enqueuePriorityAudioDownload(
                        context, ExoDownloadService::class.java, request, false,
                    )
                },
            )
        }
        if (splitArtists.isNotEmpty()) {
            item {
                CapsuleMenuListItem(
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
                    modifier = Modifier.clickable {
                        if (splitArtists.size == 1 && splitArtists[0].originalArtist != null) {
                            navController.navigate("artist/${splitArtists[0].originalArtist!!.id}")
                            onDismiss()
                        } else {
                            showSelectArtistDialog = true
                        }
                    }
                )
            }
        }
        song.album?.let { album ->
            item {
                CapsuleMenuListItem(
                    headlineContent = { Text(text = stringResource(R.string.view_album)) },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.capsule_soft_album),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        navController.navigate("album/${album.id}")
                        onDismiss()
                    }
                )
            }
        }
        item {
             CapsuleMenuListItem(
                 headlineContent = { Text(text = stringResource(R.string.details)) },
                 leadingContent = {
                     Icon(
                         painter = painterResource(R.drawable.capsule_soft_info),
                         contentDescription = null,
                     )
                 },
                 modifier = Modifier.clickable {
                      onDismiss()
                      bottomSheetPageState.show {
                          ShowMediaInfo(song.id)
                      }
                 }
             )
        }
    }
}
