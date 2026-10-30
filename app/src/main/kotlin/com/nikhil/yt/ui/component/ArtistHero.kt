package com.nikhil.yt.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import com.nikhil.yt.ui.motion.CapsuleStandardEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.nikhil.yt.R

internal val ArtistHeroMaxWidth = 450.dp
internal const val ArtistHeroArtworkHeightFactor = 1.25f
internal val ArtistMemorialActionExtraHeight = 62.dp

internal fun artistHeroArtworkHeight(availableWidth: Dp): Dp =
    availableWidth.coerceAtMost(ArtistHeroMaxWidth) * ArtistHeroArtworkHeightFactor

/** Reference composition: large photograph, low title and two rows of existing Capsule actions. */
@Composable
internal fun ArtistHeroLayout(
    background: Color,
    artwork: @Composable () -> Unit,
    title: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    topSafePadding: Dp = 0.dp,
    artworkAlpha: () -> Float = { 1f },
    memorialExtraHeight: Dp = ArtistMemorialActionExtraHeight,
) {
    BoxWithConstraints(modifier.fillMaxWidth().background(background)) {
        // Preserve the full-size portrait. The action rail begins exactly 12dp
        // below it in BOTH layouts; no photograph/button overlap.
        // The old rail always reserved the second 62dp memorial row, even for
        // living artists. Coupled with a visual action offset it created a
        // large hole above AND below their only row of buttons.
        val artworkHeight = artistHeroArtworkHeight(maxWidth)
        val firstRowHeight = if (LocalDensity.current.fontScale > 1.3f) 110.dp else 48.dp
        val actionRailHeight = firstRowHeight + memorialExtraHeight
        val heroHeight = artworkHeight + 12.dp + actionRailHeight + 14.dp
        Box(Modifier.fillMaxWidth().height(artworkHeight)
            .testTag("artist-artwork-field")) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = artworkAlpha().coerceIn(0f, 1f) }) { artwork() }
            ArtworkSurfaceFade(
                background,
                Modifier.matchParentSize(),
                portrait = true,
                topScrim = false,
                // Match album/playlist: the restrained long matte preserves
                // portrait colour rather than washing it out halfway down.
                softPortrait = false,
            )
        }
        Column(
            // Only the optional memorial row animates in height. The photo and
            // Subscribe/Shuffle retain fixed anchors, while subsequent content
            // moves smoothly instead of jumping after the lookup finishes.
            Modifier.fillMaxWidth().height(heroHeight)
                .padding(top = topSafePadding + 72.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) { title() }
            Spacer(Modifier.height(24.dp))
            // A single compact row for living artists, two for memorials.
            // Clipping the animated extra slot gives a soft candle reveal
            // without leaving an invisible 62dp placeholder.
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .height(actionRailHeight)
                    .clipToBounds()
                    .testTag("artist-action-rail"),
            ) { actions() }
        }
    }
}

@Composable
internal fun ArtistHero(
    name: String,
    thumbnailUrl: String?,
    background: Color,
    subscribed: Boolean,
    canSubscribe: Boolean,
    canShuffle: Boolean,
    showCandle: Boolean,
    onSubscribe: () -> Unit,
    onShuffle: () -> Unit,
    onCandle: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    topSafePadding: Dp = 0.dp,
    artworkAlpha: () -> Float = { 1f },
) {
    // The first row never moves. Late memorial detection only grows/shrinks
    // the second row and its list space with a small eased transition.
    val memorialExtraHeight by animateDpAsState(
        targetValue = if (showCandle) ArtistMemorialActionExtraHeight else 0.dp,
        animationSpec = tween(durationMillis = 260, easing = CapsuleStandardEasing),
        label = "artist-memorial-row-height",
    )
    val loadingLabel = stringResource(R.string.loading)

    val displayedSubscribed = subscribed
    val onSubscribeClick = onSubscribe

    ArtistHeroLayout(
        modifier = if (loading) modifier.clearAndSetSemantics { contentDescription = loadingLabel } else modifier,
        background = background,
        topSafePadding = topSafePadding,
        artworkAlpha = artworkAlpha,
        memorialExtraHeight = memorialExtraHeight,
        artwork = {
            /*
             * No figure stands in for a portrait that has not arrived yet.
             *
             * A grey person icon was drawn the instant the hero was composed and replaced a moment
             * later by the photograph, so it flashed once on every visit to an artist -- an icon
             * nobody asked for, in the largest and most prominent place on the page. What stands
             * there now while the photograph loads is a gradient: the hero's own colour, lifted a
             * little at the top so the space reads as a surface rather than a flat slab. It is also
             * what remains when there is no photograph at all, which is the same thing said
             * quietly.
             */
            Box(
                Modifier.fillMaxSize().background(background),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = capsuleArtworkUrl(thumbnailUrl, 960),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(horizontalBias = -0.1f, verticalBias = -1f),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        // The artist name now belongs to the pinned toolbar only. Keeping another copy
        // over/next to the portrait made the header feel duplicated once the toolbar became
        // persistent.
        title = {},
        actions = {
            // The same first-row anchor applies with and without the candle.
            Column {
            val stackActions = LocalDensity.current.fontScale > 1.3f
            val subscribeLabel = stringResource(if (displayedSubscribed) R.string.capsule_artist_subscribed else R.string.subscribe)
            if (stackActions) {
                CapsuleArtistAction(
                    icon = R.drawable.add,
                    label = subscribeLabel,
                    onClick = onSubscribeClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && canSubscribe,
                    selected = displayedSubscribed,
                    subscribeState = displayedSubscribed,
                )
                Spacer(Modifier.height(14.dp))
                CapsuleArtistAction(
                    R.drawable.shuffle,
                    stringResource(R.string.shuffle),
                    onShuffle,
                    Modifier.fillMaxWidth(),
                    !loading && canShuffle,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CapsuleArtistAction(
                        icon = R.drawable.add,
                        label = subscribeLabel,
                        onClick = onSubscribeClick,
                        modifier = Modifier.weight(1f),
                        enabled = !loading && canSubscribe,
                        selected = displayedSubscribed,
                        subscribeState = displayedSubscribed,
                    )
                    CapsuleArtistAction(
                        R.drawable.shuffle,
                        stringResource(R.string.shuffle),
                        onShuffle,
                        Modifier.weight(1f),
                        !loading && canShuffle,
                    )
                }
            }
            // The growing clipped rail reveals the candle without moving the
            // first row or briefly painting over the next release.
            if (showCandle) {
                Spacer(Modifier.height(14.dp))
                CapsuleArtistAction(
                    R.drawable.capsule_candle,
                    stringResource(R.string.artist_light_candle),
                    onCandle,
                    Modifier.fillMaxWidth(),
                    !loading,
                )
            }
            }
        },
    )
}

@Composable
private fun CapsuleArtistAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    subscribeState: Boolean? = null,
) {
    // For subscribing, change only the text: no icon morph, label crossfade,
    // size animation or surface tween. Keep the existing Capsule button layout.
    val containerColor = (if (selected && subscribeState == null)
        StandardChrome.selected else StandardChrome.panel).copy(alpha = 0.94f)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = if (selected && subscribeState == null) 0.30f else 0.16f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = StandardChrome.text,
            disabledContainerColor = StandardChrome.panel.copy(alpha = 0.74f),
            disabledContentColor = StandardChrome.muted,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (subscribeState == null) {
            if (icon == R.drawable.capsule_candle) {
                CapsuleCandleIcon(size = 19.dp)
            } else {
                Icon(painterResource(icon), null, Modifier.size(19.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
}
