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
 * resolved portrait URL independently from the dialog so the work can start as
 * soon as the track starts and the picker can open with warm images.
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

        val mutex = mutexes[(id.hashCode() and Int.MAX_VALUE) % mutexes.size]
        return mutex.withLock {
            peek(id)?.let { return@withLock it }

            localLookup()
                ?.takeIf { it.isNotBlank() }
                ?.let { return@withLock put(id, it) }

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
 * Warm portraits while a track is already playing rather than when the artist
 * chooser opens. Existing metadata/database thumbnails require no extra API
 * request. A remote artist lookup is only used when a chooser can actually be
 * needed (two or more navigable artists), which keeps request volume bounded.
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
        val allowNetwork = targets.size > 1

        coroutineScope {
            targets.forEach { artist ->
                launch {
                    val id = artist.id ?: return@launch
                    try {
                        val portrait =
                            ArtistPortraits.resolve(
                                id = id,
                                hintedUrl = artist.thumbnailUrl,
                                allowNetwork = allowNetwork,
                                localLookup = {
                                    database.artist(id).first()?.thumbnailUrl
                                },
                            )

                        if (!portrait.isNullOrBlank()) {
                            context.imageLoader.execute(
                                ImageRequest.Builder(context)
                                    .data(portrait)
                                    .size(192, 192)
                                    .build(),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Best effort only. Opening the picker may retry the lookup.
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
