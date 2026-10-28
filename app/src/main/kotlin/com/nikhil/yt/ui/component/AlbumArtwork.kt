package com.nikhil.yt.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.nikhil.yt.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Portrait canvas shared by album AND playlists: identical full-width cover,
 * top and bottom matte fades, pinned toolbar crossing, and capsule baseline.
 * Image alpha is applied to the photo ONLY; the page-colour fade is not dimmed.
 */
@Composable
internal fun AlbumPortraitFrame(
    background: Color,
    modifier: Modifier = Modifier,
    artworkAlpha: () -> Float = { 1f },
    artworkMotion: DetailHeaderMotionState? = null,
    artwork: @Composable BoxScope.() -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    BoxWithConstraints(
        modifier.widthIn(max = 560.dp).fillMaxWidth().background(background),
    ) {
        val height = albumCoverHeight(maxWidth, screenHeight)
        Box(
            Modifier.fillMaxWidth().height(height)
                .then(if (artworkMotion == null) Modifier
                    else Modifier.onGloballyPositioned(artworkMotion::measureArtwork)),
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = artworkAlpha().coerceIn(0f, 1f) }) {
                artwork()
            }
            ArtworkSurfaceFade(
                background = background,
                modifier = Modifier.matchParentSize(),
                portrait = true,
                topScrim = true,
                softPortrait = true,
            )
        }
    }
}

/**
 * A direct Coil AsyncImage uses actual layout constraints. Try the original
 * URL if a Google CDN resize fails, then a track cover if the collection
 * thumbnail is missing or broken. This avoids a blank black hero.
 */
@Composable
internal fun DetailPortraitImage(
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    fallbackThumbnailUrl: String? = null,
    minimumPixels: Int = 960,
) {
    val candidates = remember(thumbnailUrl, fallbackThumbnailUrl, minimumPixels) {
        listOf(
            capsuleArtworkUrl(thumbnailUrl, minimumPixels),
            thumbnailUrl,
            capsuleArtworkUrl(fallbackThumbnailUrl, minimumPixels),
            fallbackThumbnailUrl,
        ).filterNotNull().filter(String::isNotBlank).distinct()
    }
    var candidateIndex by remember(thumbnailUrl, fallbackThumbnailUrl, minimumPixels) {
        mutableIntStateOf(0)
    }
    Box(
        modifier.background(StandardChrome.panel),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.music_note),
            contentDescription = null,
            tint = StandardChrome.muted.copy(alpha = 0.38f),
            modifier = Modifier.size(56.dp),
        )
        if (candidates.isNotEmpty()) {
            AsyncImage(
                model = candidates[candidateIndex.coerceAtMost(candidates.lastIndex)],
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = {
                    if (candidateIndex < candidates.lastIndex) candidateIndex++
                },
            )
        }
    }
}

@Composable
internal fun AlbumArtwork(
    thumbnailUrl: String?,
    background: Color,
    modifier: Modifier = Modifier,
    artworkAlpha: () -> Float = { 1f },
    artworkMotion: DetailHeaderMotionState? = null,
    fallbackThumbnailUrl: String? = null,
) {
    AlbumPortraitFrame(
        background = background,
        modifier = modifier,
        artworkAlpha = artworkAlpha,
        artworkMotion = artworkMotion,
    ) {
        DetailPortraitImage(
            thumbnailUrl = thumbnailUrl,
            fallbackThumbnailUrl = fallbackThumbnailUrl,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Responsive portrait size shared by album and playlist details. */
internal fun albumCoverHeight(width: Dp, screenHeight: Dp): Dp {
    val natural = width / 0.85f
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
    AlbumPortraitFrame(
        background = background,
        modifier = modifier,
        artworkAlpha = artworkAlpha,
        artworkMotion = artworkMotion,
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(imageModifier),
        )
    }
}
