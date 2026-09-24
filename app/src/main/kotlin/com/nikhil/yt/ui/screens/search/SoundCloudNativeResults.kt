package com.nikhil.yt.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Genuine SoundCloud cards, resolved with NewPipe, never disguised YouTube matches. */
@Composable
internal fun SoundCloudNativeResults(
    query: String,
    selectedUrl: String?,
    playing: Boolean,
    loadingTrack: Boolean,
    onTrackClick: (SoundCloudCatalog.Track) -> Unit,
) {
    var result by remember(query) { mutableStateOf<SoundCloudCatalog.Result?>(null) }
    LaunchedEffect(query) {
        result = null
        result = withContext(Dispatchers.IO) { SoundCloudCatalog.search(query) }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.capsule_soundcloud_badge),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
        )
        when (val value = result) {
            null -> Status(R.string.capsule_soundcloud_loading)
            SoundCloudCatalog.Result.RateLimited -> Status(R.string.capsule_soundcloud_rate_limited)
            SoundCloudCatalog.Result.Unavailable -> Status(R.string.capsule_soundcloud_request_failed)
            is SoundCloudCatalog.Result.Tracks -> {
                if (value.items.isEmpty()) Status(R.string.capsule_soundcloud_no_results)
                value.items.forEach { track ->
                    val selected = track.permalink == selectedUrl
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackClick(track) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            )
                            Text(
                                text = track.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stringResource(R.string.capsule_soundcloud_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (selected) Text(
                                text = stringResource(
                                    when {
                                        loadingTrack -> R.string.capsule_soundcloud_loading
                                        playing -> R.string.capsule_soundcloud_pause
                                        else -> R.string.capsule_soundcloud_play
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Status(message: Int) {
    Text(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
