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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.utils.reportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Artist metadata on playback items often contains only a browse ID. Keep the
 * resolved portrait URL independently from the dialog so already-known artwork
 * can be warmed as soon as playback starts.
 */
internal object ArtistPortraits {
    private val cache = LruCache<String, String>(96)
    private val mutexes = Array(8) { Mutex() }

    fun peek(id: String?): String? = id?.let(cache::get)

    private fun put(id: String, url: String): String =
        url.also { cache.put(id, it) }

    suspend fun resolve(
        id: String,
        hintedUrl: String?,
        allowNetwork: Boolean,
        localLookup: suspend () -> String?,
    ): String? {
        hintedUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { return put(id, it) }

        peek(id)?.let { return it }

        /* Room Flow.first() is suspendable; never execute it while holding the
         * portrait mutex. A second cache check below handles races cleanly. */
        localLookup()
            ?.takeIf { it.isNotBlank() }
            ?.let { return put(id, it) }

        val mutex = mutexes[(id.hashCode() and Int.MAX_VALUE) % mutexes.size]
        return mutex.withLock {
            peek(id)?.let { return@withLock it }

            if (!allowNetwork) return@withLock null

            YouTube.artist(id)
                .getOrThrow()
                .artist
                .thumbnail
                ?.takeIf { it.isNotBlank() }
                ?.let { put(id, it) }
        }
    }
}

/**
 * Warm artist portraits at track start without starting any extra YouTube API
 * request in the background. We only use URLs that are already present in the
 * playback metadata or local database and prime Coil's cache with those.
 *
 * If an artist has no known portrait yet, the chooser may resolve it normally
 * when the user opens the menu. This keeps startup traffic predictable while
 * still making the common case instant.
 */
@Composable
fun PreloadArtistPortraits(artists: List<MediaMetadata.Artist>) {
    val database = LocalDatabase.current
    val context = LocalContext.current
    val targets =
        remember(artists) {
            artists
                .filter { !it.id.isNullOrBlank() }
                .distinctBy { it.id }
        }

    LaunchedEffect(targets) {
        if (targets.isEmpty()) return@LaunchedEffect

        coroutineScope {
            targets.forEach { artist ->
                launch {
                    val id = artist.id ?: return@launch
                    try {
                        val portrait =
                            ArtistPortraits.resolve(
                                id = id,
                                hintedUrl = artist.thumbnailUrl,
                                allowNetwork = false,
                                localLookup = {
                                    database.artist(id).first()?.thumbnailUrl
                                },
                            )

                        if (!portrait.isNullOrBlank()) {
                            context.imageLoader.enqueue(
                                ImageRequest.Builder(context)
                                    .data(portrait)
                                    .size(192, 192)
                                    .build(),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Best effort only. Opening the picker may retry normally.
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistSelectionItem(
    name: String,
    artistId: String?,
    thumbnailUrl: String? = null,
    onClick: () -> Unit,
) {
    val database = LocalDatabase.current
    val portrait by
        produceState(
            thumbnailUrl?.takeIf { it.isNotBlank() } ?: ArtistPortraits.peek(artistId),
            artistId,
            thumbnailUrl,
        ) {
            value = thumbnailUrl?.takeIf { it.isNotBlank() } ?: ArtistPortraits.peek(artistId)
            if (value == null && !artistId.isNullOrBlank()) {
                try {
                    value =
                        ArtistPortraits.resolve(
                            id = artistId,
                            hintedUrl = thumbnailUrl,
                            allowNetwork = true,
                            localLookup = {
                                database.artist(artistId).first()?.thumbnailUrl
                            },
                        )
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
internal fun ArtistSelectionRow(
    name: String,
    artistId: String?,
    portrait: Any?,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("artistName:$artistId"),
            )
        },
        leadingContent = {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .testTag("artistPortrait:$artistId"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.trim().firstOrNull()?.uppercase() ?: "♪",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AsyncImage(
                    model = portrait,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !artistId.isNullOrBlank(), onClick = onClick),
    )
}
