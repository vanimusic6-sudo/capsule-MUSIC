package com.nikhil.yt.ui.menu

import android.content.Intent
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.nikhil.yt.*
import com.nikhil.yt.constants.ListThumbnailSize
import com.nikhil.yt.constants.ThumbnailCornerRadius
import com.nikhil.yt.playback.ExoDownloadService
import com.nikhil.yt.soundcloud.*
import com.nikhil.yt.ui.component.SoundCloudSourceIcon
import com.nikhil.yt.ui.component.VeluneLoader

@Composable
internal fun SoundCloudTrackMenu(track: SoundCloudCatalog.Track, navController: NavController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val mediaId = soundCloudMediaId(track.permalink)
    val download by LocalDownloadUtil.current.getDownload(mediaId).collectAsState(initial = null)

    LazyColumn(contentPadding = WindowInsets.systemBars.asPaddingValues()) {
        item {
            ListItem(
                headlineContent = { Text(track.title, Modifier.basicMarquee(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.size(6.dp)); SoundCloudSourceIcon()
                    }
                },
                leadingContent = {
                    Box(Modifier.size(ListThumbnailSize).clip(RoundedCornerShape(ThumbnailCornerRadius)), Alignment.Center) {
                        AsyncImage(track.artworkUrl, null, Modifier.fillMaxWidth())
                    }
                }
            )
        }
        item {
            when(download?.state) {
                Download.STATE_COMPLETED -> ListItem(
                    headlineContent = { Text(stringResource(R.string.remove_download), color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(painterResource(R.drawable.offline), null) },
                    modifier = Modifier.clickable {
                        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, mediaId, false); onDismiss()
                    }
                )
                Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> ListItem(
                    headlineContent = { Text(stringResource(R.string.downloading)) },
                    leadingContent = { VeluneLoader(24.dp) },
                    modifier = Modifier.clickable {
                        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, mediaId, false); onDismiss()
                    }
                )
                else -> ListItem(
                    headlineContent = { Text(stringResource(R.string.action_download)) },
                    leadingContent = { Icon(painterResource(R.drawable.download), null) },
                    modifier = Modifier.clickable {
                        database.transaction { insert(track.toSoundCloudMetadata()) }
                        val req = DownloadRequest.Builder(mediaId, track.permalink.toUri())
                            .setCustomCacheKey(mediaId).setData(track.title.toByteArray()).build()
                        DownloadService.sendAddDownload(context, ExoDownloadService::class.java, req, false)
                        onDismiss()
                    }
                )
            }
        }
        track.uploaderUrl?.let { artistUrl ->
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.view_artist)) },
                    leadingContent = { Icon(painterResource(R.drawable.artist), null) },
                    modifier = Modifier.clickable {
                        navController.navigate("soundcloud/profile?url=" + android.net.Uri.encode(artistUrl)); onDismiss()
                    }
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.share)) },
                leadingContent = { Icon(painterResource(R.drawable.share), null) },
                modifier = Modifier.clickable {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type="text/plain"; putExtra(Intent.EXTRA_TEXT, track.permalink)
                    }, null)); onDismiss()
                }
            )
        }
    }
}
