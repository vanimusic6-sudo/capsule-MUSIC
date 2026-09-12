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

/** Loading and loaded headers use the same spacing and cover geometry. */
@Composable
internal fun AlbumHeaderLayout(
    artwork: @Composable () -> Unit,
    title: @Composable () -> Unit,
    metadata: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        artwork()
        Spacer(Modifier.height(16.dp))
        title()
        Spacer(Modifier.height(8.dp))
        metadata()
        Spacer(Modifier.height(24.dp))
        actions()
        Spacer(Modifier.height(56.dp))
    }
}

@Composable
internal fun AlbumHeaderPlaceholder(background: Color, modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.loading)
    val placeholder = StandardChrome.muted.copy(alpha = 0.18f)
    AlbumHeaderLayout(
        modifier = modifier.clearAndSetSemantics { contentDescription = loading },
        artwork = {
            AlbumArtworkLayers(ColorPainter(StandardChrome.panel), blurred = null, background = background)
        },
        title = {
            Box(Modifier.fillMaxWidth(0.6f).height(32.dp).padding(vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp)).background(placeholder))
        },
        metadata = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(64.dp, 96.dp, 72.dp).forEach { width ->
                    Box(Modifier.width(width).height(28.dp).clip(CircleShape).background(placeholder))
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
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(5) { Box(Modifier.size(20.dp).clip(CircleShape).background(placeholder)) }
                }
            }
        },
    )
}
