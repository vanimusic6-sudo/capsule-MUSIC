package com.nikhil.yt.ui.component

import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.nikhil.yt.R

/** Reference composition: large photograph, low title and two rows of existing Capsule actions. */
@Composable
internal fun ArtistHeroLayout(
    background: Color,
    artwork: @Composable () -> Unit,
    title: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    topSafePadding: Dp = 0.dp,
) {
    BoxWithConstraints(modifier.fillMaxWidth().background(background)) {
        // Keep the reference's bottom-aligned actions, lowered by 16 dp.
        // Artwork itself reaches the physical top edge; only overlay content respects safe insets.
        val referenceWidth = maxWidth.coerceAtMost(450.dp)
        val heroHeight = referenceWidth * 1.69f + 16.dp
        Box(Modifier.fillMaxWidth().height(referenceWidth * 1.36f)) {
            Box(Modifier.fillMaxSize()) { artwork() }
            // No dark veil across the forehead of the artist image. The toolbar remains
            // inset for touch safety; only the photo itself reaches the physical top edge.
            ArtworkSurfaceFade(background, Modifier.matchParentSize(), portrait = true, topScrim = false)
        }
        Column(
            Modifier.fillMaxWidth().heightIn(min = heroHeight)
                .padding(top = topSafePadding + 72.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) { title() }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) { actions() }
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
    showRadio: Boolean,
    canRadio: Boolean,
    onSubscribe: () -> Unit,
    onShuffle: () -> Unit,
    onRadio: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    topSafePadding: Dp = 0.dp,
) {
    val loadingLabel = stringResource(R.string.loading)

    // A follow/unfollow tap is a local user intent. Keep that intent visually authoritative for
    // the lifetime of this hero instead of letting a delayed database/server snapshot undo the
    // button a frame later. The database + durable subscription outbox still reconcile in the
    // background, so this only removes the misleading subscribe -> unsubscribe flicker.
    var localSubscribedIntent by remember { mutableStateOf<Boolean?>(null) }
    val displayedSubscribed = localSubscribedIntent ?: subscribed
    val onSubscribeClick = {
        localSubscribedIntent = !displayedSubscribed
        onSubscribe()
    }

    ArtistHeroLayout(
        modifier = if (loading) modifier.clearAndSetSemantics { contentDescription = loadingLabel } else modifier,
        background = background,
        topSafePadding = topSafePadding,
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
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(horizontalBias = -0.1f, verticalBias = -1f),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        title = {
            if (loading && name.isBlank()) {
                Box(Modifier.fillMaxWidth(0.66f).height(38.dp).clip(RoundedCornerShape(10.dp))
                    .background(StandardChrome.muted.copy(alpha = 0.18f)))
            } else {
                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 36.sp),
                    fontWeight = FontWeight.Bold,
                    color = StandardChrome.text.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Alpha belongs to the glyphs, so the photograph shows through the letters.
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        actions = {
            val stackActions = LocalDensity.current.fontScale > 1.3f
            val subscribeLabel = stringResource(if (displayedSubscribed) R.string.subscribed else R.string.subscribe)
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
            if (showRadio) {
                Spacer(Modifier.height(14.dp))
                CapsuleArtistAction(
                    R.drawable.radio,
                    stringResource(R.string.radio),
                    onRadio,
                    Modifier.fillMaxWidth(),
                    !loading && canRadio,
                )
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
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = if (selected) 0.30f else 0.16f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = (if (selected) StandardChrome.selected else StandardChrome.panel).copy(alpha = 0.94f),
            contentColor = StandardChrome.text,
            disabledContainerColor = StandardChrome.panel.copy(alpha = 0.74f),
            disabledContentColor = StandardChrome.muted,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (subscribeState != null) {
            CapsuleSubscribeIcon(
                subscribed = subscribeState,
                tint = LocalContentColor.current,
                modifier = Modifier.size(19.dp),
            )
        } else {
            Icon(painterResource(icon), null, Modifier.size(19.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
