package com.nikhil.yt.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.ImageRequest

/**
 * Album artwork keeps the photograph sharp and dissolves it into the page with the exact same
 * matte surface veil used by the artist hero. No blurred duplicate layer is rendered underneath.
 */
@Composable
fun AlbumArtwork(
    thumbnailUrl: String?,
    background: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizeResolver = rememberConstraintsSizeResolver()
    val request = remember(thumbnailUrl, context, sizeResolver) {
        ImageRequest.Builder(context)
            .data(thumbnailUrl)
            .size(sizeResolver)
            .build()
    }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)

    AlbumArtworkLayers(
        painter = painter,
        blurred = null,
        background = background,
        modifier = modifier,
        imageModifier = Modifier.then(sizeResolver),
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AlbumArtworkLayers(
    painter: Painter,
    blurred: ImageBitmap?,
    background: Color,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier,
) {
    Box(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .background(background),
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(imageModifier),
        )

        // Identical matte fade to ArtistHero: the page colour replaces the photograph gradually.
        ArtworkSurfaceFade(background, Modifier.matchParentSize(), portrait = true)
    }
}
