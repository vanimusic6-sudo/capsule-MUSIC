package com.nikhil.yt.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * Square 87%-width artwork; centred regular-weight title; raised metadata tab
 * BEHIND the front action tray (not two separate vertically stacked cards).
 * Keep the home theme's actual background instead of hardcoded black, and do
 * not add blurred cover gradients that would change the user's sketch.
 */
@Composable
internal fun CapsuleDetailHero(
    title: String,
    metrics: List<CapsuleHeroMetric>,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    artwork: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().background(StandardChrome.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The app toolbar sits over the top of the scrolling detail hero.
        Spacer(Modifier.height(100.dp))
        Box(
            Modifier.fillMaxWidth(0.87f)
                .aspectRatio(1f)
                .testTag("capsule:detail-artwork")
                .clip(RoundedCornerShape(46.dp))
                .background(StandardChrome.panel)
                .border(
                    1.dp,
                    StandardChrome.muted.copy(alpha = 0.2f),
                    RoundedCornerShape(46.dp),
                ),
        ) {
            artwork()
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            color = StandardChrome.text,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp),
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            subtitle()
        }
        Spacer(Modifier.height(16.dp))
        CapsuleDetailActionCluster(metrics = metrics, actions = actions)
        Spacer(Modifier.height(34.dp))
    }
}

/**
 * One continuous outline follows the entire two-level shape: it goes around
 * the raised stats tab, extends along each shoulder of the bottom action tray,
 * then rounds the bottom corners. Neither half has its own separate border.
 * This is a single path rather than two overlapping outlined rectangles.
 */
@Composable
internal fun CapsuleDetailActionCluster(
    metrics: List<CapsuleHeroMetric>,
    actions: @Composable RowScope.() -> Unit,
) {
    val panel = StandardChrome.panel
    val outline = StandardChrome.muted.copy(alpha = 0.32f)
    val tabFraction = if (metrics.size > 2) 0.94f else 0.78f

    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(91.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val tabLeft = w * (1f - tabFraction) / 2f
            val tabRight = w - tabLeft
            val shoulderY = h - 60.dp.toPx()
            val upperRadius = minOf(24.dp.toPx(), (tabRight - tabLeft) / 3f)
            // Preserve the stepped silhouette on narrower 3-stat albums.
            val shoulderRadius = minOf(24.dp.toPx(), tabLeft * 0.75f)
            val bottomRadius = 24.dp.toPx()

            val combined = Path().apply {
                moveTo(tabLeft + upperRadius, 0f)
                lineTo(tabRight - upperRadius, 0f)
                quadraticBezierTo(tabRight, 0f, tabRight, upperRadius)
                lineTo(tabRight, shoulderY)
                lineTo(w - shoulderRadius, shoulderY)
                quadraticBezierTo(w, shoulderY, w, shoulderY + shoulderRadius)
                lineTo(w, h - bottomRadius)
                quadraticBezierTo(w, h, w - bottomRadius, h)
                lineTo(bottomRadius, h)
                quadraticBezierTo(0f, h, 0f, h - bottomRadius)
                lineTo(0f, shoulderY + shoulderRadius)
                quadraticBezierTo(0f, shoulderY, shoulderRadius, shoulderY)
                lineTo(tabLeft, shoulderY)
                lineTo(tabLeft, upperRadius)
                quadraticBezierTo(tabLeft, 0f, tabLeft + upperRadius, 0f)
                close()
            }
            drawPath(combined, color = panel)
            drawPath(combined, color = outline, style = Stroke(width = 1.dp.toPx()))
        }

        Row(
            modifier = Modifier.align(Alignment.TopCenter)
                .fillMaxWidth(tabFraction).height(37.dp)
                .padding(horizontal = 7.dp)
                .testTag("capsule:detail-metadata-tab"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            metrics.forEach { metric ->
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(metric.icon),
                        contentDescription = null,
                        tint = StandardChrome.muted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = metric.text,
                        color = StandardChrome.muted,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = if (metrics.size > 2) 11.sp else 13.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(60.dp).padding(horizontal = 7.dp)
                .testTag("capsule:detail-actions"),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * Album-only control layout. Both the album facts and all five actions belong
 * to ONE rounded capsule with ONE outer outline; unlike the separate stepped
 * playlist silhouette there is no protruding stats tab or overlapping border.
 *
 * This intentionally reuses the same CapsuleHeroMetric / PlaylistAction data
 * and interactive download button as the redesigned playlists.
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
        artwork = {
            val covers = thumbnails.filter(String::isNotBlank).take(4)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (covers.size) {
                    0 -> if (loading) VeluneLoader(size = 30.dp) else Icon(
                        painter = painterResource(placeholderIcon),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = StandardChrome.muted.copy(alpha = 0.7f),
                    )
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
