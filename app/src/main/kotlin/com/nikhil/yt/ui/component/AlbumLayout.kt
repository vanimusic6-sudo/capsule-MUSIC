package com.nikhil.yt.ui.component
import com.nikhil.yt.ui.utils.rememberCapsuleFlingBehavior

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
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
    flingBehavior: FlingBehavior = rememberCapsuleFlingBehavior(),
    content: LazyListScope.() -> Unit,
    topBar: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(background)) {
        LazyColumn(
            flingBehavior = flingBehavior,
            overscrollEffect = null,
            state = state,
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .asPaddingValues(),
            content = content,
        )
        topBar()
    }
}

/** Artwork begins at the window edge beneath the pinned toolbar. */
internal val AlbumArtworkTopSpacing = 0.dp

/** One bottom gap for albums and every playlist, including loading placeholders. */
internal val DetailHeroBottomSpacing = 23.dp

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
            // Caption is overlaid inside the portrait; controls keep their baseline.
            Spacer(Modifier.height(6.dp))
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
internal fun AlbumHeaderPlaceholder(background: Color, modifier: Modifier = Modifier, motion: DetailHeaderMotionState? = null) {
    val loading = stringResource(R.string.loading)
    val placeholder = StandardChrome.muted.copy(alpha = 0.18f)
    AlbumHeaderLayout(
        modifier = modifier.clearAndSetSemantics { contentDescription = loading },
        showTitle = false,
        artworkTopSpacing = AlbumArtworkTopSpacing,
        artwork = {
            AlbumPortraitFrame(background, artworkMotion = motion) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.music_note),
                        contentDescription = null,
                        tint = placeholder,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
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
                for ((icon, label) in listOf(
                    R.drawable.favorite_border to R.string.action_like,
                    R.drawable.play_outline to R.string.play,
                    R.drawable.shuffle to R.string.shuffle,
                    R.drawable.download to R.string.download,
                    R.drawable.more_vert to R.string.more,
                )) {
                    PlaylistAction(icon, stringResource(label), {}, enabled = false)
                }
            }
        },
        bottomSpacing = DetailHeroBottomSpacing,
    )
}
