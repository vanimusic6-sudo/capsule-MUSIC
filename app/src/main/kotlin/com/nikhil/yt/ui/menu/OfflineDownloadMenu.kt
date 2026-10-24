package com.nikhil.yt.ui.menu

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.discovery.ExperimentalDownload
import com.nikhil.yt.discovery.OfflineAudioQueue
import com.nikhil.yt.ui.component.ItemThumbnail
import com.nikhil.yt.ui.component.MenuSurfaceSection
import java.time.LocalDateTime
import kotlinx.coroutines.launch

/**
 * Local MediaStore operations; never invoke YouTube likeVideo, YT radio or
 * YouTube playlist synchronization for an independent recording.
 */
@Composable
fun OfflineDownloadMenu(
    file: ExperimentalDownload.SavedAudio,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val metadata = remember(file) { OfflineAudioQueue.metadata(file) }
    val librarySong by database.song(metadata.id).collectAsState(initial = null)
    var choosePlaylist by remember { mutableStateOf(false) }

    // The shared dialog can target LOCAL Capsule playlists only.
    // YouTube's online playlists must never receive a MediaStore ID.
    AddToPlaylistDialog(
        isVisible = choosePlaylist,
        allowSyncing = false,
        onGetSong = {
            database.withTransaction {
                insert(metadata) { it.copy(isLocal = true) }
            }
            listOf(metadata.id)
        },
        onDismiss = {
            choosePlaylist = false
        },
        onAddComplete = { _, _ ->
            choosePlaylist = false
            onDismiss()
        },
    )

    @Composable
    fun Action(label: String, drawable: Int, destructive: Boolean = false, action: () -> Unit) {
        ListItem(
            headlineContent = {
                Text(label, color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface)
            },
            leadingContent = {
                Icon(
                    painter = painterResource(drawable),
                    tint = if (destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
            },
            modifier = Modifier.clickable(onClick = action),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (file.artwork != null) {
                ItemThumbnail(
                    thumbnailUrl = file.artwork,
                    isActive = false,
                    isPlaying = false,
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(12.dp),
                )
            } else {
                Icon(painter = painterResource(R.drawable.music_note),
                    contentDescription = null, modifier = Modifier.size(48.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(file.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(file.artist + " · Audius", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        MenuSurfaceSection(modifier = Modifier.padding(vertical = 4.dp)) {
            Action(stringResource(R.string.play_next), R.drawable.playlist_play) {
                player?.playNext(OfflineAudioQueue.mediaItem(file))
                onDismiss()
            }
            Action(stringResource(R.string.add_to_queue), R.drawable.queue_music) {
                player?.addToQueue(OfflineAudioQueue.mediaItem(file))
                onDismiss()
            }
            Action(stringResource(R.string.add_to_playlist), R.drawable.playlist_add) {
                choosePlaylist = true
            }
            Action(
                stringResource(if (librarySong?.song?.liked == true)
                    R.string.capsule_unlike_local else R.string.capsule_like_local),
                R.drawable.favorite,
            ) {
                scope.launch {
                    database.withTransaction {
                        val existing = getSongById(metadata.id)
                        if (existing == null) {
                            insert(metadata) {
                                it.copy(isLocal = true, liked = true,
                                    likedDate = LocalDateTime.now(),
                                    inLibrary = LocalDateTime.now())
                            }
                        } else update(existing.song.localToggleLike())
                    }
                }
            }
            Action(
                stringResource(if (librarySong?.song?.inLibrary != null)
                    R.string.remove_from_library else R.string.add_to_library),
                R.drawable.library_music,
            ) {
                scope.launch {
                    database.withTransaction {
                        val existing = getSongById(metadata.id)
                        if (existing == null) {
                            insert(metadata) {
                                it.copy(isLocal = true, inLibrary = LocalDateTime.now())
                            }
                        } else update(existing.song.toggleLibrary())
                    }
                }
            }
            Action(stringResource(R.string.share), R.drawable.share) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/*"
                    putExtra(Intent.EXTRA_STREAM, file.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, null))
                onDismiss()
            }
        }
        MenuSurfaceSection(modifier = Modifier.padding(vertical = 8.dp)) {
            Action(stringResource(R.string.remove_download), R.drawable.offline, destructive = true) {
                onRemove()
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
