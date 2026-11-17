/* Capsule MUSIC — stable decoded-cover handoff between tracks. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale

internal const val CAPSULE_COVER_HANDOFF_MS = 390

/** One visual pair, not an animated grey placeholder or two independent artwork clocks. */
@Composable
internal fun CapsuleTrackArtworkHandoff(
    mediaId: String,
    decodedPainter: Painter?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    var displayedId by remember { mutableStateOf<String?>(mediaId) }
    var incoming by remember { mutableStateOf<Painter?>(decodedPainter) }
    var outgoing by remember { mutableStateOf<Painter?>(null) }
    val incomingOpacity = remember { Animatable(1f) }

    LaunchedEffect(mediaId, decodedPainter) {
        if (displayedId != mediaId) {
            // A -> B -> C mid-dissolve: keep the more visible real bitmap as the outgoing one.
            // Never brighten a half-faded old frame back to full by replacing the whole layer.
            outgoing = if (incomingOpacity.value >= 0.5f) incoming ?: outgoing
                else outgoing ?: incoming
            incoming = null
            displayedId = mediaId
            incomingOpacity.snapTo(0f)
        }

        if (decodedPainter != null && incoming !== decodedPainter) {
            // Only decoded, full-size imagery reaches this node. Coil Loading/Retry never
            // changes the outgoing picture, so background or network stalls cannot grey it.
            outgoing = incoming ?: outgoing
            incoming = decodedPainter
            incomingOpacity.snapTo(if (outgoing == null) 1f else 0f)
        }

        if (incoming != null && outgoing != null) {
            incomingOpacity.animateTo(
                1f,
                tween(durationMillis = CAPSULE_COVER_HANDOFF_MS, easing = FastOutSlowInEasing),
            )
            outgoing = null
        }
    }

    Box(modifier) {
        outgoing?.let { painter ->
            Image(painter, null, Modifier.fillMaxSize(), contentScale = contentScale)
        }
        incoming?.let { painter ->
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = incomingOpacity.value },
            )
        }
    }
}
