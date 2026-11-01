package com.nikhil.yt.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/** A finite eased arrival, including a disk/memory cache hit.
 * Uses only draw-layer alpha; it does not restart image requests, animate a
 * placeholder forever, or change the album's independent photo scroll fade.
 */
@Composable
internal fun CapsuleFadeAsyncImage(
    model: Any?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    identity: Any? = model,
) {
    val context = LocalContext.current
    // The singleton loader enables a separate native Coil crossfade. Running
    // that *and* our decode-driven reveal produced a double opacity ramp.
    // Keep one animation across disk hits, memory hits and network responses.
    val request = remember(context, model) {
        if (model is ImageRequest) model
        else ImageRequest.Builder(context).data(model).crossfade(false).build()
    }
    var decoded by remember(identity) { mutableStateOf(false) }
    val arrival = rememberArtworkArrivalAlpha(identity, decoded)
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        onSuccess = { decoded = true },
        modifier = modifier.graphicsLayer { alpha = arrival.value },
    )
}
