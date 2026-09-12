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

private const val CAPSULE_FULL_ARTWORK_PREFETCH_SIZE = 960

/**
 * The full player is normally collapsed when a track begins. Warm its high-res
 * cover immediately instead of waiting for the user to expand the sheet.
 *
 * This uses Coil's normal non-blocking enqueue path and only the thumbnail URL
 * already present in playback metadata. It does not perform any additional
 * metadata/API lookup in the background.
 */
@Composable
internal fun PreloadCapsuleTrackAssets(mediaMetadata: MediaMetadata?) {
    val context = LocalContext.current
    val artworkUrl = mediaMetadata?.thumbnailUrl?.toHighResThumbnail()

    LaunchedEffect(mediaMetadata?.id, artworkUrl) {
        if (artworkUrl.isNullOrBlank()) return@LaunchedEffect

        runCatching {
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .size(
                        CAPSULE_FULL_ARTWORK_PREFETCH_SIZE,
                        CAPSULE_FULL_ARTWORK_PREFETCH_SIZE,
                    )
                    .build(),
            )
        }
    }

    PreloadArtistPortraits(mediaMetadata?.artists.orEmpty())
}
