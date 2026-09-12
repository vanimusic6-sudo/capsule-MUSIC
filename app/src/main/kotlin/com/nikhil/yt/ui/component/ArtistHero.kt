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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
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
        // The fade and photograph end together; no uncovered strip below the image.
        val referenceWidth = maxWidth.coerceAtMost(450.dp)
        val heroHeight = referenceWidth * 1.69f + 16.dp
        Box(Modifier.fillMaxWidth().height(referenceWidth * 1.36f)) {
            Box(
                Modifier.fillMaxWidth()
                    .padding(top = referenceWidth * 0.06f)
                    .height(referenceWidth * 1.30f),
            ) { artwork() }
            ArtworkSurfaceFade(background, Modifier.matchParentSize(), portrait = true)
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
    val context = LocalContext.current
    val portraitRequest = remember(context, thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(thumbnailUrl)
            .allowHardware(false)
            .transformations(ArtistPortraitBlurTransformation)
            .build()
    }
    var artworkFailed by remember(thumbnailUrl) { mutableStateOf(thumbnailUrl.isNullOrBlank()) }
    ArtistHeroLayout(
        modifier = if (loading) modifier.clearAndSetSemantics { contentDescription = loadingLabel } else modifier,
        background = background,
        topSafePadding = topSafePadding,
        artwork = {
            Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                if (artworkFailed) {
                    Icon(painterResource(R.drawable.person), null,
                        Modifier.size(88.dp).testTag("artist-artwork-placeholder"),
                        tint = StandardChrome.muted.copy(alpha = 0.35f))
                }
                AsyncImage(
                    model = portraitRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onLoading = { artworkFailed = false },
                    onSuccess = { artworkFailed = false },
                    onError = { artworkFailed = true },
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
            val subscribeLabel = stringResource(if (subscribed) R.string.subscribed else R.string.subscribe)
            val subscribeIcon = if (subscribed) R.drawable.done else R.drawable.add
            if (stackActions) {
                CapsuleArtistAction(subscribeIcon, subscribeLabel, onSubscribe, Modifier.fillMaxWidth(), !loading && canSubscribe, subscribed)
                Spacer(Modifier.height(14.dp))
                CapsuleArtistAction(R.drawable.shuffle, stringResource(R.string.shuffle), onShuffle, Modifier.fillMaxWidth(), !loading && canShuffle)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CapsuleArtistAction(subscribeIcon, subscribeLabel, onSubscribe, Modifier.weight(1f), !loading && canSubscribe, subscribed)
                    CapsuleArtistAction(R.drawable.shuffle, stringResource(R.string.shuffle), onShuffle, Modifier.weight(1f), !loading && canShuffle)
                }
            }
            if (showRadio) {
                Spacer(Modifier.height(14.dp))
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
