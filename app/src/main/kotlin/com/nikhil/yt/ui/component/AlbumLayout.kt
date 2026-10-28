package com.nikhil.yt.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R

/** Artwork starts at the window edge; the overlaid toolbar handles its own status-bar inset. */
@Composable
internal fun AlbumScreenLayout(
    background: Color,
    state: LazyListState,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
    topBar: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(background)) {
        LazyColumn(
            state = state,
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .asPaddingValues(),
            content = content,
        )
        topBar()
    }
}

/**
 * No empty ribbon above the portrait: artwork begins at the window's top edge
 * underneath the pinned artist-style toolbar. The old 44dp external spacer
 * is incorporated into the portrait height instead, so buttons keep their Y.
 */
internal val AlbumArtworkTopSpacing = 0.dp

/** One bottom gap for albums and every playlist, including loading placeholders. */
internal val DetailHeroBottomSpacing = 34.dp

/** Loading and loaded headers use the same spacing and cover geometry. */
@Composable
internal fun AlbumHeaderLayout(
    artwork: @Composable () -> Unit,
    title: @Composable () -> Unit,
    metadata: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomSpacing: androidx.compose.ui.unit.Dp = DetailHeroBottomSpacing,
    showTitle: Boolean = true,
    artworkTopSpacing: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (artworkTopSpacing > 0.dp) Spacer(Modifier.height(artworkTopSpacing))
        artwork()
        if (showTitle) {
            Spacer(Modifier.height(10.dp))
            title()
            Spacer(Modifier.height(6.dp))
            metadata()
            Spacer(Modifier.height(12.dp))
        } else {
            // The actual album title lives in the pinned Artist-style toolbar.
            // Move the photograph down by 44dp, using the former caption's
            // space rather than pushing download/play controls below the player.
            Spacer(Modifier.height(14.dp))
            metadata()
        }
        actions()
        Spacer(Modifier.height(bottomSpacing))
    }
}

/**
 * Album skeleton keeps the ORIGINAL portrait 0.85 artwork, title position and
 * matte fade. The metadata and action rows occupy the same single album capsule.
 * Its height matches the loaded content to prevent jumping on first render.
 */
@Composable
internal fun AlbumHeaderPlaceholder(background: Color, modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.loading)
    val placeholder = StandardChrome.muted.copy(alpha = 0.18f)
    AlbumHeaderLayout(
        modifier = modifier.clearAndSetSemantics { contentDescription = loading },
        showTitle = false,
        artworkTopSpacing = AlbumArtworkTopSpacing,
        artwork = {
            AlbumArtworkLayers(
                ColorPainter(StandardChrome.panel),
                blurred = null,
                background = background,
            )
        },
        title = {},
        metadata = {},
        actions = {
            CapsuleAlbumControls(
                metrics = listOf(
                    CapsuleHeroMetric(R.drawable.music_note, "—"),
                    CapsuleHeroMetric(R.drawable.calendar_today, "—"),
                    CapsuleHeroMetric(R.drawable.timer, "—"),
                ),
            ) {
                repeat(5) {
                    Box(
                        Modifier.weight(1f).height(60.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(placeholder),
                        )
                    }
                }
            }
        },
        bottomSpacing = DetailHeroBottomSpacing,
    )
}
