package com.nikhil.yt.ui.screens.search

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
import com.nikhil.yt.constants.SoundCloudOAuthTokenKey
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native catalogue cards. SoundCloud audio is not added to the YouTube queue. */
@Composable
internal fun SoundCloudNativeResults(query: String) {
    val (token, _) = rememberPreference(SoundCloudOAuthTokenKey, "")
    var result by remember(query, token) {
        mutableStateOf<SoundCloudCatalog.Result?>(null)
    }
    LaunchedEffect(query, token) {
        result = if (token.isBlank()) {
            SoundCloudCatalog.Result.MissingToken
        } else {
            withContext(Dispatchers.IO) {
                SoundCloudCatalog.search(query, token)
            }
        }
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
            null -> Text(
                text = stringResource(R.string.capsule_soundcloud_loading),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            SoundCloudCatalog.Result.MissingToken -> Status(R.string.capsule_soundcloud_needs_token)
            SoundCloudCatalog.Result.Unauthorized -> Status(R.string.capsule_soundcloud_invalid_token)
            SoundCloudCatalog.Result.RateLimited -> Status(R.string.capsule_soundcloud_rate_limited)
            SoundCloudCatalog.Result.Unavailable -> Status(R.string.capsule_soundcloud_request_failed)
            is SoundCloudCatalog.Result.Tracks -> {
                if (value.items.isEmpty()) Status(R.string.capsule_soundcloud_no_results)
                value.items.forEach { track ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
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
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = track.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(R.string.capsule_soundcloud_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (value.items.isNotEmpty()) {
                    Status(R.string.capsule_soundcloud_native_playback_pending)
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
