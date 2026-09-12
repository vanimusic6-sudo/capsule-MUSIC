package com.nikhil.yt.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One image request supplies the sharp cover and its small, reusable blur layer. */
@Composable
fun AlbumArtwork(
    thumbnailUrl: String?,
    background: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizeResolver = rememberConstraintsSizeResolver()
    val request = remember(thumbnailUrl, context, sizeResolver) {
        ImageRequest.Builder(context).data(thumbnailUrl).size(sizeResolver).allowHardware(false).build()
    }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
    val state by painter.state.collectAsState()
    val image = (state as? AsyncImagePainter.State.Success)?.result?.image
    var blurred by remember(image) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image) {
        if (image != null) {
            blurred = withContext(Dispatchers.Default) {
                createAlbumArtworkBlur(image.toBitmap()).asImageBitmap()
            }
        }
    }
    AlbumArtworkLayers(
        painter = painter,
        blurred = blurred,
        background = background,
        modifier = modifier,
        imageModifier = Modifier.then(sizeResolver),
    )
}

@Composable
internal fun AlbumArtworkLayers(
    painter: Painter,
    blurred: ImageBitmap?,
    background: Color,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier,
) {
    Box(modifier.widthIn(max = 560.dp).fillMaxWidth().aspectRatio(0.85f).background(background)) {
        Image(painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(imageModifier))
        if (blurred != null) {
            Image(
                bitmap = blurred, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val edgeMask = Brush.verticalGradient(
                            0f to Color.Transparent, 0.52f to Color.Transparent,
                            0.86f to Color.White, 1f to Color.White,
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(edgeMask, blendMode = BlendMode.DstIn)
                        }
                    },
            )
        }
        ArtworkSurfaceFade(background, Modifier.matchParentSize())
    }
}
