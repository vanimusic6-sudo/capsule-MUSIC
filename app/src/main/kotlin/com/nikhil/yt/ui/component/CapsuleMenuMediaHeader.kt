/*
 * Capsule Music — shared More-menu media identity row.
 * Original GPL-3.0 UI; immutable layout sizes, no background animation.
 */
package com.nikhil.yt.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.yt.R

/**
 * Same title hierarchy, thumbnail, spacing and corner language on player,
 * songs and playlists. The optional trailing button is independently tappable.
 */
@Composable
fun CapsuleMenuMediaHeader(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    overline: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val tileShape = CapsuleMenuTileShape
    // Inset the artwork's radius by the same 8 dp as the surrounding row.
    // Derive it from the shared tile token so a later theme change stays concentric.
    val thumbnailShape = remember(tileShape) {
        val outer = tileShape as? RoundedCornerShape
        if (outer == null) RoundedCornerShape(12.dp)
        else RoundedCornerShape(object : CornerSize {
            override fun toPx(shapeSize: Size, density: Density): Float =
                (outer.topStart.toPx(shapeSize, density) - with(density) { 8.dp.toPx() })
                    .coerceIn(0f, shapeSize.minDimension / 2f)
        })
    }
    Surface(
        shape = tileShape,
        color = CapsuleMenuBlack,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            if (thumbnailUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier.size(52.dp).clip(thumbnailShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.capsule_soft_music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(thumbnailShape),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                if (!overline.isNullOrBlank()) {
                    Text(
                        text = overline,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailingContent?.invoke()
        }
    }
}

/** Preserves complex artist/album row behaviour under the same player surface. */
@Composable
fun CapsuleMenuHeaderSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CapsuleMenuBlack,
        shape = CapsuleMenuTileShape,
        content = content,
    )
}
