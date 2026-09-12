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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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

/** A square full-width portrait. Large accessibility text may extend the content below it. */
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
        val portraitSize = maxWidth
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            artwork()
            ArtworkSurfaceFade(background, Modifier.matchParentSize())
        }
        Column(
            Modifier.fillMaxWidth().heightIn(min = portraitSize)
                .padding(start = 20.dp, end = 20.dp, top = topSafePadding + 72.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            title()
            Spacer(Modifier.height(16.dp))
            actions()
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
    ArtistHeroLayout(
        modifier = if (loading) modifier.clearAndSetSemantics { contentDescription = loadingLabel } else modifier,
        background = background,
        topSafePadding = topSafePadding,
        artwork = {
            Box(Modifier.fillMaxSize().background(StandardChrome.panel), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.person), null, Modifier.size(88.dp), tint = StandardChrome.muted.copy(alpha = 0.35f))
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
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
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 39.sp),
                    fontWeight = FontWeight.Bold,
                    color = StandardChrome.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        actions = {
            val stackActions = LocalDensity.current.fontScale > 1.3f
            val subscribeLabel = stringResource(if (subscribed) R.string.subscribed else R.string.subscribe)
            val subscribeIcon = if (subscribed) R.drawable.done else R.drawable.add
            if (stackActions) {
                CapsuleArtistAction(subscribeIcon, subscribeLabel, onSubscribe, Modifier.fillMaxWidth(), !loading && canSubscribe, subscribed)
                Spacer(Modifier.height(10.dp))
                CapsuleArtistAction(R.drawable.shuffle, stringResource(R.string.shuffle), onShuffle, Modifier.fillMaxWidth(), !loading && canShuffle)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CapsuleArtistAction(subscribeIcon, subscribeLabel, onSubscribe, Modifier.weight(1f), !loading && canSubscribe, subscribed)
                    CapsuleArtistAction(R.drawable.shuffle, stringResource(R.string.shuffle), onShuffle, Modifier.weight(1f), !loading && canShuffle)
                }
            }
            if (showRadio) {
                Spacer(Modifier.height(10.dp))
                CapsuleArtistAction(R.drawable.radio, stringResource(R.string.radio), onRadio, Modifier.fillMaxWidth(), !loading && canRadio)
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
        Icon(painterResource(icon), null, Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
