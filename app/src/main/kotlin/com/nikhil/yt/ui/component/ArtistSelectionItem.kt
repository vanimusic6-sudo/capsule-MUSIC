package com.nikhil.yt.ui.component

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.utils.reportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Artist metadata often contains only a browse ID. Resolve portraits only while
// the picker is open; reuse successful lookups across all player/menu entry points.
private object ArtistPortraits {
    private val cache = LruCache<String, String>(64)
    private val mutex = Mutex()

    suspend fun get(id: String): String? = mutex.withLock {
        cache.get(id)?.let { return@withLock it }
        YouTube.artist(id).getOrThrow().artist.thumbnail?.takeIf { it.isNotBlank() }
            ?.also { cache.put(id, it) }
    }
}

@Composable
fun ArtistSelectionItem(name: String, artistId: String?, thumbnailUrl: String? = null, onClick: () -> Unit) {
    val database = LocalDatabase.current
    val portrait by produceState(thumbnailUrl, artistId, thumbnailUrl) {
        value = thumbnailUrl?.takeIf { it.isNotBlank() }
        if (value == null && !artistId.isNullOrBlank()) {
            try {
                value = database.artist(artistId).first()?.thumbnailUrl?.takeIf { it.isNotBlank() }
                    ?: ArtistPortraits.get(artistId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportException(failure)
            }
        }
    }
    ArtistSelectionRow(name, artistId, portrait, onClick)
}

@Composable
internal fun ArtistSelectionRow(name: String, artistId: String?, portrait: Any?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag("artistPortrait:$artistId"),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.trim().firstOrNull()?.uppercase() ?: "♪", color = MaterialTheme.colorScheme.onSurfaceVariant)
                AsyncImage(model = portrait, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !artistId.isNullOrBlank(), onClick = onClick),
    )
}
