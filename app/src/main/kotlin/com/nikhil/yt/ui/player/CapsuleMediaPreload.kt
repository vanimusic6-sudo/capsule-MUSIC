/*
 * Capsule MUSIC
 * Warm the visual assets for the current track while the mini player is visible.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.component.PreloadArtistPortraits
import kotlinx.coroutines.delay

private const val CAPSULE_FULL_ARTWORK_PREFETCH_SIZE = 960
private const val CAPSULE_VISUAL_PREFETCH_SETTLE_MS = 1_200L

/**
 * The full player is normally collapsed when a track begins. Warm its high-res
 * cover while Capsule is actually on screen instead of waiting for the user to
 * expand the sheet.
 *
 * Background playback must stay cheap: Composition remains alive when the app
 * is hidden, so without the lifecycle guard every automatic track change would
 * still start a 960 px artwork request and artist portrait preloads that nobody
 * can see. Returning to the app flips [appIsOnScreen] back to true and warms the
 * current track normally.
 *
 * This uses Coil's normal non-blocking enqueue path and only the thumbnail URL
 * already present in playback metadata. It does not perform any additional
 * metadata/API lookup in the background.
 */
@Composable
internal fun PreloadCapsuleTrackAssets(mediaMetadata: MediaMetadata?) {
    val context = LocalContext.current
    val artworkUrl = mediaMetadata?.thumbnailUrl?.toHighResThumbnail()
    val onScreen = appIsOnScreen()
    var stableEnoughToWarm by
        remember(mediaMetadata?.id, artworkUrl) {
            mutableStateOf(false)
        }

    LaunchedEffect(mediaMetadata?.id, artworkUrl, onScreen) {
        stableEnoughToWarm = false
        if (!onScreen || artworkUrl.isNullOrBlank()) return@LaunchedEffect

        /*
         * Rapid skips are the worst possible moment to decode 960 px artwork and artist portraits:
         * every request is obsolete before the user could open the player. Let the selected track
         * survive a short settle window first. This coroutine is cancelled automatically on the
         * next media id, so skipped tracks do no high-resolution visual work at all.
         */
        delay(CAPSULE_VISUAL_PREFETCH_SETTLE_MS)
        stableEnoughToWarm = true

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

    if (onScreen && stableEnoughToWarm) {
        PreloadArtistPortraits(mediaMetadata?.artists.orEmpty())
    }
}
