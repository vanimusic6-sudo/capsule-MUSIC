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

/** Shared cover, counters and one action tray for local, online and automatic playlists. */
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
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth(0.84f).widthIn(max = 420.dp).aspectRatio(1f)
                .clip(RoundedCornerShape(22.dp)).background(StandardChrome.panel),
            contentAlignment = Alignment.Center,
        ) {
            val covers = thumbnails.filter { it.isNotBlank() }.take(4)
            if (covers.isEmpty()) {
                if (loading) VeluneLoader(size = 30.dp)
                else Icon(painterResource(placeholderIcon), null, Modifier.size(72.dp), tint = StandardChrome.muted)
            } else if (covers.size == 1) {
                AsyncImage(covers.first(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(Modifier.fillMaxSize()) {
                    repeat(2) { row ->
                        Row(Modifier.weight(1f)) {
                            repeat(2) { column ->
                                AsyncImage(
                                    covers[(row * 2 + column) % covers.size], null,
                                    Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (subtitle != null) {
            Spacer(Modifier.height(14.dp))
            subtitle()
        }
        Spacer(Modifier.height(28.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaylistMetadataChip(R.drawable.music_note, songCount)
            duration?.let { PlaylistMetadataChip(R.drawable.timer, it) }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f).widthIn(max = 480.dp),
            color = StandardChrome.selected,
            contentColor = StandardChrome.text,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = 0.22f)),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
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

@Composable
internal fun RowScope.PlaylistAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = StandardChrome.text,
    loading: Boolean = false,
) {
    androidx.compose.material3.IconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label },
    ) {
        if (loading) VeluneLoader(size = 22.dp)
        else Icon(painterResource(icon), null, Modifier.size(25.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.35f))
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
