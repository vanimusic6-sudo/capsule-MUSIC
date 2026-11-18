/* Capsule MUSIC — single-resolution, track-scoped artwork. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale

/**
 * Only the fully decoded cover is ever shown. In particular there is NO second
 * AsyncImage for a 360px preview and NO low -> high-resolution swap on screen.
 * Media ID scopes the painter: the previous song cannot leak across skips.
 */
internal const val CAPSULE_COVER_HANDOFF_MS = 205

@Composable
internal fun CapsuleTrackArtworkHandoff(
    mediaId: String,
    decodedPainter: Painter?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    key(mediaId) {
        if (decodedPainter != null) {
            val alpha = remember { Animatable(0f) }
            LaunchedEffect(decodedPainter) {
                alpha.animateTo(1f, tween(CAPSULE_COVER_HANDOFF_MS))
            }
            Image(
                painter = decodedPainter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = modifier.graphicsLayer { this.alpha = alpha.value },
            )
        }
    }
}
