package com.nikhil.yt.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
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
    artworkAlpha: () -> Float = { 1f },
    artworkMotion: DetailHeaderMotionState? = null,
) {
    val context = LocalContext.current
    val sizeResolver = rememberConstraintsSizeResolver()
    val request = remember(thumbnailUrl, context, sizeResolver) {
        ImageRequest.Builder(context)
            .data(capsuleArtworkUrl(thumbnailUrl, 960))
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
        artworkAlpha = artworkAlpha,
        artworkMotion = artworkMotion,
    )
}

/**
 * Reserve enough of a small screen for the title and unified action capsule
 * ABOVE the mini-player. On larger screens the original 0.85 portrait artwork
 * is retained; compact screens gently crop its faded lower portion.
 *
 * Responsive sizing belongs to the album only; playlist art stays square.
 */
internal fun albumCoverHeight(width: Dp, screenHeight: Dp): Dp {
    val natural = width / 0.85f
    // Shift the album and matching playlist action baselines down slightly,
    // without adding a sharp band above the full-bleed portrait.
    return minOf(natural, maxOf(natural * 0.83f, screenHeight * 0.50f)) + 12.dp
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AlbumArtworkLayers(
    painter: Painter,
    blurred: ImageBitmap?,
    background: Color,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier,
    artworkAlpha: () -> Float = { 1f },
    artworkMotion: DetailHeaderMotionState? = null,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    BoxWithConstraints(
        modifier.widthIn(max = 560.dp).fillMaxWidth().background(background),
    ) {
        val artworkHeight = albumCoverHeight(maxWidth, screenHeight)
        Box(Modifier.fillMaxWidth().height(artworkHeight)
            .then(if (artworkMotion != null) Modifier.onGloballyPositioned(artworkMotion::measureArtwork) else Modifier)
        ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(imageModifier)
                .graphicsLayer { alpha = artworkAlpha().coerceIn(0f, 1f) },
        )

        // Identical matte fade to ArtistHero: the page colour replaces the photograph gradually.
        ArtworkSurfaceFade(
            background = background,
            modifier = Modifier.matchParentSize(),
            portrait = true,
            // The album portrait now starts 44dp below the top of the sheet.
            // Feather its top edge into the same surface, never a hard horizontal line.
            topScrim = true,
            softPortrait = true,
        )
        }
    }
}
