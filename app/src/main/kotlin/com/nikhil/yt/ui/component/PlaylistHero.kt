package com.nikhil.yt.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nikhil.yt.R

/**
 * Playlist and album use the SAME header geometry: edge-to-edge 0.85 portrait artwork,
 * the same matte fade, centred title, compact metadata and one 56dp action tray.
 * Only the playlist-specific actions supplied by the caller differ.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaylistHero(
    thumbnails: List<String>,
    songCount: String,
    duration: String? = null,
    modifier: Modifier = Modifier,
    @DrawableRes placeholderIcon: Int = R.drawable.queue_music,
    loading: Boolean = false,
    title: String = "",
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val background = if (StandardChrome.isDark) Color(0xFF090909) else StandardChrome.background
    AlbumHeaderLayout(
        modifier = modifier,
        artwork = {
            Box(
                Modifier.widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .aspectRatio(0.85f)
                    .background(background),
            ) {
                val covers = thumbnails.filter(String::isNotBlank).take(4)
                Box(Modifier.fillMaxSize().background(StandardChrome.panel), contentAlignment = Alignment.Center) {
                    when (covers.size) {
                        0 -> if (loading) VeluneLoader(size = 30.dp) else
                            Icon(painterResource(placeholderIcon), null, Modifier.size(72.dp), tint = StandardChrome.muted)
                        1 -> AsyncImage(
                            model = covers.first(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        else -> Column(Modifier.fillMaxSize()) {
                            repeat(2) { row ->
                                Row(Modifier.weight(1f)) {
                                    repeat(2) { column ->
                                        AsyncImage(
                                            model = covers[(row * 2 + column) % covers.size],
                                            contentDescription = null,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Identical artwork-to-surface fade as album artwork, including portrait sizing.
                ArtworkSurfaceFade(background, Modifier.matchParentSize(), portrait = true, topScrim = false)
            }
        },
        title = {
            if (loading && title.isBlank()) {
                Box(
                    Modifier.fillMaxWidth(0.6f).height(32.dp)
                        .padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp))
                        .background(StandardChrome.muted.copy(alpha = 0.18f)),
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        },
        metadata = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                subtitle?.invoke()
                if (subtitle != null) Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PlaylistMetadataChip(R.drawable.music_note, songCount)
                    duration?.let { PlaylistMetadataChip(R.drawable.timer, it) }
                }
            }
        },
        actions = {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                shape = RoundedCornerShape(16.dp),
                color = StandardChrome.panel.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = 0.22f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        },
    )
}

@Composable
private fun PlaylistMetadataChip(@DrawableRes icon: Int, text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = StandardChrome.panel) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = StandardChrome.muted)
            Text(text, color = StandardChrome.muted, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The same single-tray icon treatment as AlbumAction, while keeping playlist semantics,
 * enabled states and download spinner supplied by each existing playlist screen.
 */
@Composable
internal fun RowScope.PlaylistAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = StandardChrome.text,
    loading: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).height(56.dp).semantics { contentDescription = label },
    ) {
        if (loading) {
            VeluneLoader(size = 22.dp)
        } else {
            Icon(
                painter = painterResource(icon),
                contentDescription = null, // Parent IconButton already announces the action once.
                modifier = Modifier.size(26.dp),
                tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            )
        }
    }
}

