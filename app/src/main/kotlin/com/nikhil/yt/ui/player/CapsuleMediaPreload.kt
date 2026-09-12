/*
 * Capsule MUSIC
 * Warm the visual assets for the current track while the mini player is visible.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.component.PreloadArtistPortraits
import kotlinx.coroutines.CancellationException

private const val CAPSULE_FULL_ARTWORK_PREFETCH_SIZE = 960

/**
 * The full player is normally collapsed when a track begins. Warm its high-res
 * cover immediately instead of waiting for the user to expand the sheet. Artist
 * portraits are warmed at the same time by [PreloadArtistPortraits].
 *
 * Prefetch is deliberately best-effort: playback must never wait for artwork.
 */
@Composable
internal fun PreloadCapsuleTrackAssets(mediaMetadata: MediaMetadata?) {
    val context = LocalContext.current
    val artworkUrl = mediaMetadata?.thumbnailUrl?.toHighResThumbnail()

    LaunchedEffect(mediaMetadata?.id, artworkUrl) {
        if (artworkUrl.isNullOrBlank()) return@LaunchedEffect

        try {
            context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .size(
                        CAPSULE_FULL_ARTWORK_PREFETCH_SIZE,
                        CAPSULE_FULL_ARTWORK_PREFETCH_SIZE,
                    )
                    .build(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A later AsyncImage load is still allowed to retry normally.
        }
    }

    PreloadArtistPortraits(mediaMetadata?.artists.orEmpty())
}
