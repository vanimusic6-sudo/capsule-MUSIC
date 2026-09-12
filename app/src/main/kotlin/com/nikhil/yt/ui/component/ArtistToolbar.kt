package com.nikhil.yt.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R

/** Bare artwork controls at the reference height; their 48 dp touch targets are retained. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistToolbar(
    name: String,
    overArtwork: Boolean,
    canShare: Boolean,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val foreground = if (overArtwork) Color.White else StandardChrome.text
    val artworkFraction by animateFloatAsState(
        targetValue = if (overArtwork) 1f else 0f,
        animationSpec = tween(200),
        label = "artistToolbarCollapse",
    )
    val buttonColors = IconButtonDefaults.iconButtonColors(
        containerColor = Color.Transparent,
        contentColor = foreground,
    )
    val shareColors = IconButtonDefaults.iconButtonColors(
        containerColor = Color.Transparent,
        contentColor = foreground.copy(alpha = if (canShare) 1f else 0.38f),
    )
    TopAppBar(
        modifier = modifier,
        // One shared, centred row keeps the name and icons aligned throughout scrolling.
        // 96 dp preserves the artwork controls' existing centre; the compact bar uses 64 dp.
        windowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        expandedHeight = (64f + 32f * artworkFraction).dp,
        title = {
            Text(
                name,
                modifier = Modifier.alpha(1f - artworkFraction)
                    .then(if (overArtwork) Modifier.clearAndSetSemantics {} else Modifier),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(
                onBack,
                onBackLongClick,
                colors = buttonColors,
            ) {
                Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.back))
            }
        },
        actions = {
            IconButton(
                onCopyLink,
                {},
                enabled = canShare,
                colors = shareColors,
            ) {
                Icon(painterResource(R.drawable.link), stringResource(R.string.copy_link))
            }
            IconButton(
                onShare,
                {},
                enabled = canShare,
                colors = shareColors,
            ) {
                Icon(painterResource(R.drawable.share), stringResource(R.string.share))
            }
        },
        colors = if (overArtwork) TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            navigationIconContentColor = foreground,
            actionIconContentColor = foreground,
            titleContentColor = foreground,
        ) else TopAppBarDefaults.topAppBarColors(),
    )
}
