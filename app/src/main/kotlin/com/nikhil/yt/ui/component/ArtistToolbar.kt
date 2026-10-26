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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R

internal val ArtistToolbarCompactHeight = 64.dp

/**
 * Match the same scroll fade as the portrait. Avoid an opaque toolbar covering
 * still-visible artwork: that double-darkened the top ~80dp into a distinct stripe.
 * The underlying surface is already exactly StandardChrome.background.
 */
internal fun artistToolbarSurfaceAlpha(artworkFade: Float): Float {
    // The header itself already fades the portrait into StandardChrome.background.
    // Painting the SAME colour at the SAME alpha above it would composite the
    // fades twice (remaining photo = (1 - fade)^2), producing a dark toolbar
    // stripe even though the scroll values are perfectly synchronized.
    // Do not paint over the still-visible portrait. Only seal the bar in the
    // last 2% of that dissolve, before list content reaches its hit area.
    val fade = artworkFade.coerceIn(0f, 1f)
    return ((fade - 0.98f) / 0.02f).coerceIn(0f, 1f)
}

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
    artworkFade: Float = if (overArtwork) 0f else 1f,
    artworkFadeProvider: () -> Float = { artworkFade },
) {
    // Resolve Compose theme colour once inside composition, NOT from
    // drawBehind (which must never invoke a @Composable theme getter).
    val artistSurfaceColor = StandardChrome.background
    val fade = artworkFade.coerceIn(0f, 1f)
    val foreground = lerp(Color.White, StandardChrome.text, fade)
    // The hero owns the photographic dissolve. The toolbar only adds an opaque
    // surface after the image is almost completely gone, avoiding double tint.
    // Draw-phase sampling still keeps fast flings free of composition lag.
    val buttonColors = IconButtonDefaults.iconButtonColors(
        containerColor = Color.Transparent,
        contentColor = foreground,
    )
    val shareColors = IconButtonDefaults.iconButtonColors(
        containerColor = Color.Transparent,
        contentColor = foreground.copy(alpha = if (canShare) 1f else 0.38f),
    )
    TopAppBar(
        modifier = modifier.drawBehind {
            drawRect(artistSurfaceColor.copy(
                alpha = artistToolbarSurfaceAlpha(artworkFadeProvider()),
            ))
        },
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
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                navigationIconContentColor = foreground,
                actionIconContentColor = foreground,
                titleContentColor = foreground,
            ),
    )
}
