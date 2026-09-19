package com.nikhil.yt.ui.component

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.yt.R

/**
 * Shared cover, counters and actions for local, online and automatic playlists.
 *
 * Built to match the artist screen, because the two are the same kind of place and used to look
 * like different apps. The artist screen lets its artwork reach the edges and dissolve into the
 * page; the playlist cover sat in the middle as a hard-edged card on a flat background, with every
 * action crammed into one bordered strip.
 *
 * Three things bring them together, and all three already existed or cost nothing:
 *
 * - the cover is full width and hands over to the page through [ArtworkSurfaceFade] — the same
 *   component the artist hero uses, so the seam is gone rather than merely softened;
 * - [ArtworkGlow] adds the light the cover spills onto the interface below it. Drawn, never
 *   blurred;
 * - the actions are individual rounded buttons on the panel colour with a hairline border, which is
 *   the artist screen's button, instead of one long tray.
 *
 * The parameters are unchanged, so the five playlist screens did not have to be touched.
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
    subtitle: (@Composable () -> Unit)? = null,
    secondaryAction: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val background = StandardChrome.background
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Square, and capped so it does not swallow a tablet. The cover reaches both edges: an
            // inset cover cannot dissolve into the page, it can only sit on it.
            val coverHeight = maxWidth.coerceAtMost(480.dp)
            Box(Modifier.fillMaxWidth().height(coverHeight)) {
                Box(
                    Modifier.fillMaxSize().background(StandardChrome.panel),
                    contentAlignment = Alignment.Center,
                ) {
                    val covers = thumbnails.filter { it.isNotBlank() }.take(4)
                    if (covers.isEmpty()) {
                        if (loading) {
                            VeluneLoader(size = 30.dp)
                        } else {
                            Icon(
                                painterResource(placeholderIcon), null, Modifier.size(72.dp),
                                tint = StandardChrome.muted,
                            )
                        }
                    } else if (covers.size == 1) {
                        AsyncImage(
                            covers.first(), null, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            repeat(2) { row ->
                                Row(Modifier.weight(1f)) {
                                    repeat(2) { column ->
                                        AsyncImage(
                                            covers[(row * 2 + column) % covers.size], null,
                                            Modifier.weight(1f).fillMaxHeight(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Hand over to the page, then let the cover light what is underneath. The glow goes
                // on top of the fade on purpose: underneath it, the fade would wash it away exactly
                // where it is meant to be seen.
                ArtworkSurfaceFade(background, Modifier.matchParentSize())
                ArtworkGlow(StandardChrome.text, Modifier.matchParentSize())
            }
        }
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            subtitle()
        }
        Spacer(Modifier.height(18.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaylistMetadataChip(R.drawable.music_note, songCount)
            duration?.let { PlaylistMetadataChip(R.drawable.timer, it) }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 480.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
        secondaryAction?.invoke()
    }
}

@Composable
private fun PlaylistMetadataChip(@DrawableRes icon: Int, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(StandardChrome.panel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(17.dp), tint = StandardChrome.muted)
        Text(text, color = StandardChrome.muted, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One action, shaped like the artist screen's buttons.
 *
 * It used to be a bare `IconButton` inside a single long bordered tray, which is why a playlist
 * read as a toolbar and an artist read as a page. Same rounding, same hairline border, same panel
 * colour as [CapsuleArtistAction] — icon only, because a playlist offers up to five of these and
 * five labels do not fit a phone.
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
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(16.dp),
        color = StandardChrome.panel.copy(alpha = if (enabled) 0.94f else 0.74f),
        contentColor = tint,
        border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = 0.16f)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                VeluneLoader(size = 22.dp)
            } else {
                Icon(
                    painterResource(icon), null, Modifier.size(24.dp),
                    tint = if (enabled) tint else tint.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
internal fun PlaylistMixAction(onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick, enabled = enabled, modifier = Modifier.padding(top = 6.dp)) {
        Icon(painterResource(R.drawable.mix), null, Modifier.size(18.dp), tint = StandardChrome.muted)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.start_mix), color = StandardChrome.muted)
    }
}
