package com.nikhil.yt.ui.component

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R

internal val ArtistToolbarCompactHeight = 64.dp

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
        windowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        // The bar is permanently pinned. Only its surface/title visibility changes as the
        // portrait passes underneath; its geometry never grows or collapses.
        expandedHeight = ArtistToolbarCompactHeight,
        title = {
            Text(
                name,
                modifier = Modifier.testTag("artist-toolbar-title"),
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
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = if (overArtwork) Color.Transparent else StandardChrome.background,
                scrolledContainerColor = if (overArtwork) Color.Transparent else StandardChrome.background,
                navigationIconContentColor = foreground,
                actionIconContentColor = foreground,
                titleContentColor = foreground,
            ),
    )
}
