package com.nikhil.yt.ui.menu

import android.content.Intent
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalDownloadUtil
import com.nikhil.yt.R
import com.nikhil.yt.constants.ListThumbnailSize
import com.nikhil.yt.constants.ThumbnailCornerRadius
import com.nikhil.yt.playback.ExoDownloadService
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.soundcloud.soundCloudMediaId
import com.nikhil.yt.ui.component.SoundCloudSourceIcon
import com.nikhil.yt.ui.component.VeluneLoader

@Composable
internal fun SoundCloudTrackMenu(
    track: SoundCloudCatalog.Track,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val downloadUtil = LocalDownloadUtil.current
    val mediaId = soundCloudMediaId(track.permalink)
    val download by downloadUtil
        .getDownload(mediaId)
        .collectAsState(initial = null)
    val pendingSoundCloud by downloadUtil.soundCloudPending.collectAsState()
    val downloadState =
        if (mediaId in pendingSoundCloud) Download.STATE_QUEUED else download?.state

    LazyColumn(
        contentPadding = WindowInsets.systemBars.asPaddingValues(),
    ) {
        item {
            ListItem(
                headlineContent = {
                    Text(
                        text = track.title,
                        modifier = Modifier.basicMarquee(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = track.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.size(6.dp))
                        SoundCloudSourceIcon()
                    }
                },
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(ListThumbnailSize)
                            .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }

        item {
            when (downloadState) {
                Download.STATE_COMPLETED -> {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.remove_download),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.offline),
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.clickable {
                            DownloadService.sendRemoveDownload(
                                context,
                                ExoDownloadService::class.java,
                                mediaId,
                                false,
                            )
                            onDismiss()
                        },
                    )
                }

                Download.STATE_QUEUED,
                Download.STATE_DOWNLOADING -> {
                    ListItem(
                        headlineContent = {
                            Text(text = stringResource(R.string.downloading))
                        },
                        leadingContent = {
                            VeluneLoader(size = 24.dp)
                        },
                        modifier = Modifier.clickable {
                            DownloadService.sendRemoveDownload(
                                context,
                                ExoDownloadService::class.java,
                                mediaId,
                                false,
                            )
                            onDismiss()
                        },
                    )
                }

                else -> {
                    ListItem(
                        headlineContent = {
                            Text(text = stringResource(R.string.action_download))
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.download),
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.clickable {
                            downloadUtil.enqueueSoundCloud(track)
                            onDismiss()
                        },
                    )
                }
            }
        }

        track.uploaderUrl?.let { artistUrl ->
            item {
                ListItem(
                    headlineContent = {
                        Text(text = stringResource(R.string.view_artist))
                    },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.artist),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        navController.navigate(
                            "soundcloud/profile?url=" + android.net.Uri.encode(artistUrl),
                        )
                        onDismiss()
                    },
                )
            }
        }

        item {
            ListItem(
                headlineContent = {
                    Text(text = stringResource(R.string.share))
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.share),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, track.permalink)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                    onDismiss()
                },
            )
        }
    }
}
