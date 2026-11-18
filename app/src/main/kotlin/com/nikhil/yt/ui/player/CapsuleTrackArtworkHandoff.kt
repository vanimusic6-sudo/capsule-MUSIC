/* Capsule MUSIC — immediate, track-scoped artwork without stale-song ghosts. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/** The only remaining optical softening: the preview -> full-resolution bitmap of ONE song. */
internal const val CAPSULE_COVER_HANDOFF_MS = 205
internal const val CAPSULE_PREVIEW_ARRIVAL_MS = 180

/**
 * A -> B -> C must show C, never retain A while C's full-size bitmap downloads.
 *
 * The current song's thumbnail is a temporary same-song visual fallback. Coil usually has
 * it in memory from the mini-player; no extra preview coroutine or frame timer is needed.
 * A 180 ms arrival softens the *current* thumbnail, followed by a 205 ms
 * same-track HQ replacement. Old-song painters never participate in either fade.
 */
@Composable
internal fun CapsuleTrackArtworkHandoff(
    mediaId: String,
    decodedPainter: Painter?,
    thumbnailUrl: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    key(mediaId) {
        val context = LocalContext.current
        // Scope the fallback request to this song. Retiring the old ID disposes both images
        // immediately, even if the next high-resolution request stalls for seconds.
        val previewRequest = remember(context, thumbnailUrl) {
            ImageRequest.Builder(context)
                .data(thumbnailUrl?.takeIf(String::isNotBlank))
                .size(360, 360)
                .crossfade(false)
                .build()
        }
        var previewReady by remember { mutableStateOf(false) }
        val previewAlpha by animateFloatAsState(
            targetValue = if (previewReady) 1f else 0f,
            animationSpec = tween(CAPSULE_PREVIEW_ARRIVAL_MS),
            label = "capsuleCurrentCoverPreview",
        )
        // Even if Coil returns a cached painter immediately after a track skip,
        // one short optical settle makes the cover switch less like a hard cut.
        val coverAlpha = remember { Animatable(0f) }
        LaunchedEffect(decodedPainter) {
            if (decodedPainter == null) coverAlpha.snapTo(0f)
            else coverAlpha.animateTo(1f, tween(CAPSULE_COVER_HANDOFF_MS))
        }
        Box(modifier) {
            // A preview is only ever from the CURRENT media ID, not a former track.
            if (decodedPainter == null || coverAlpha.value < 0.999f) {
                if (!thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = previewRequest,
                        contentDescription = null,
                        onSuccess = { previewReady = true },
                        onError = { previewReady = false },
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = previewAlpha },
                    )
                }
            }
            decodedPainter?.let { ready ->
                Image(
                    painter = ready,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = coverAlpha.value },
                )
            }
        }
    }
}
