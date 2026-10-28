package com.nikhil.yt.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.testTag
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

/** The same caption and icon geometry for both playlists and albums. */
internal data class CapsuleHeroMetric(@DrawableRes val icon: Int, val text: String)

/**
 * Drawing-derived shared detail layout.
 *
 * Cover and controls use matching 24.dp margins; centred title; a unified
 * controls capsule containing both stats and actions, matching album screens.
 * Keep the home theme's surface instead of hardcoded black; no added gradients.
 */
@Composable
internal fun CapsuleDetailHero(
    title: String,
    metrics: List<CapsuleHeroMetric>,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    artwork: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    artworkAlpha: () -> Float = { 1f },
    titleMotion: DetailHeaderMotionState? = null,
) {
    val bodyBackground = StandardChrome.background
    AlbumHeaderLayout(
        modifier = modifier,
        showTitle = false,
        // The same 44dp portrait inset as albums. Keep online-playlist author
        // text without pushing its action capsule under the mini-player.
        artworkTopSpacing = AlbumArtworkTopSpacing -
            if (subtitle != null) 22.dp else 0.dp,
        artwork = {
            AlbumPortraitFrame(
                background = bodyBackground,
                modifier = Modifier.testTag("capsule:detail-artwork"),
                artworkAlpha = artworkAlpha,
                artworkMotion = titleMotion,
            ) {
                artwork()
            }
        },
        title = {},
        metadata = {
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                subtitle()
                Spacer(Modifier.height(6.dp))
            }
        },
        actions = { CapsuleAlbumControls(metrics = metrics, actions = actions) },
    )
}

/**
 * Shared playlist + album controls: metadata and actions live inside ONE
 * outlined capsule, without an extra raised stats tab or a second border.
 * Preserve the established actions and the collection-download controller.
 */
@Composable
internal fun CapsuleAlbumControls(
    metrics: List<CapsuleHeroMetric>,
    actions: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            .testTag("capsule:album-controls"),
        color = StandardChrome.panel,
        shape = RoundedCornerShape(27.dp),
        border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(35.dp)
                    .padding(horizontal = 12.dp)
                    .testTag("capsule:album-metrics"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                metrics.forEach { metric ->
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(metric.icon),
                            contentDescription = null,
                            tint = StandardChrome.muted,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = metric.text,
                            color = StandardChrome.muted,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = if (metrics.size > 2) 11.sp else 13.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp)
                    .padding(horizontal = 7.dp)
                    .testTag("capsule:album-actions"),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

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
    year: String? = null,
    artworkAlpha: () -> Float = { 1f },
    titleMotion: DetailHeaderMotionState? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val metrics = buildList {
        add(CapsuleHeroMetric(R.drawable.music_note, songCount))
        if (year != null) add(CapsuleHeroMetric(R.drawable.calendar_today, year))
        if (duration != null) add(CapsuleHeroMetric(R.drawable.timer, duration))
    }
    CapsuleDetailHero(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        metrics = metrics,
        artworkAlpha = artworkAlpha,
        titleMotion = titleMotion,
        artwork = {
            val covers = thumbnails.filter(String::isNotBlank).take(4)
            Box(Modifier.fillMaxSize().background(StandardChrome.panel), contentAlignment = Alignment.Center) {
                when (covers.size) {
                    0 -> if (loading) VeluneLoader(size = 30.dp) else Icon(
                        painter = painterResource(placeholderIcon),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = StandardChrome.muted.copy(alpha = 0.7f),
                    )
                    1 -> AsyncImage(
                        model = capsuleArtworkUrl(covers.first(), 960),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    else -> Column(Modifier.fillMaxSize()) {
                        repeat(2) { row ->
                            Row(Modifier.weight(1f)) {
                                repeat(2) { column ->
                                    AsyncImage(
                                        model = capsuleArtworkUrl(covers[(row * 2 + column) % covers.size], 640),
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
        },
        actions = actions,
    )
}

/** Keeps all existing playlist callbacks and download interactions untouched. */
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
        modifier = Modifier.weight(1f).height(60.dp).semantics { contentDescription = label },
    ) {
        if (loading) {
            VeluneLoader(size = 22.dp)
        } else {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            )
        }
    }
}
