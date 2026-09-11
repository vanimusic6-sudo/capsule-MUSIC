package com.nikhil.yt.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Full-bleed artwork fades into the page without a second image or palette request. */
@Composable
fun AlbumArtwork(thumbnailUrl: String?, background: Color, modifier: Modifier = Modifier) {
    Box(modifier.widthIn(max = 560.dp).fillMaxWidth().aspectRatio(0.96f)) {
        AsyncImage(model = thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to background.copy(alpha = 0.35f),
                    0.12f to Color.Transparent,
                    0.68f to Color.Transparent,
                    1f to background,
                ),
            ),
        )
    }
}
