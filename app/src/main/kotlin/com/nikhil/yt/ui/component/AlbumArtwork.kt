package com.nikhil.yt.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.nikhil.yt.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Portrait canvas shared by album AND playlists: identical full-width cover,
 * top and bottom matte fades, pinned toolbar crossing, and capsule baseline.
 * Image alpha is applied to the photo ONLY; the page-colour fade is not dimmed.
 */
@Composable
internal fun AlbumPortraitFrame(
    background: Color,
    modifier: Modifier = Modifier,
    artworkAlpha: () -> Float = { 1f },
    artworkMotion: DetailHeaderMotionState? = null,
    caption: @Composable BoxScope.() -> Unit = {},
    artwork: @Composable BoxScope.() -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    BoxWithConstraints(
        modifier.widthIn(max = 560.dp).fillMaxWidth().background(background),
    ) {
        val height = albumCoverHeight(maxWidth, screenHeight)
        Box(
            Modifier.fillMaxWidth().height(height)
                .then(if (artworkMotion == null) Modifier
                    else Modifier.onGloballyPositioned(artworkMotion::measureArtwork)),
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = artworkAlpha().coerceIn(0f, 1f) }) {
                artwork()
            }
            ArtworkSurfaceFade(
                background = background,
                modifier = Modifier.matchParentSize(),
                portrait = true,
                // Same uninterrupted full-bleed top edge as ArtistHero:
                // the old opaque top scrim disguised the removed spacer as
                // yet another grey band for the first ~14% of the photo.
                topScrim = false,
                // Same static matte dissolve as ArtistHero. Previously the
                // album-only softPortrait veil started much higher and made
                // covers look faded long before their scroll transition.
                softPortrait = false,
            )
            // Keep the name within the already-existing dark matte instead
            // of shifting the control capsule below the mini-player.
            caption()
        }
    }
}

/** ArchiveTune-style hero name: centred just above the collection capsule. */
@Composable
internal fun BoxScope.CapsuleDetailCoverTitle(
    title: String,
    motion: DetailHeaderMotionState?,
) {
    if (title.isBlank()) return
    val foreground = StandardChrome.text
    val alpha = motion?.coverTitleAlpha() ?: 1f
    val hidden = alpha <= .5f
    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp).padding(bottom = 16.dp)) {
        // This parent owns the untransformed anchor. The moving child never
        // changes the caption slot or the control capsule's measured geometry.
        Box(Modifier.then(if (motion == null) Modifier else
            Modifier.onGloballyPositioned(motion::measureCoverTitle))) {
            Text(
                text = title,
                modifier = Modifier.detailCoverTitleMotion(motion).testTag("capsule:detail-title")
                    .semantics { if (hidden) hideFromAccessibility() },
                color = foreground.copy(alpha = foreground.alpha * alpha),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 25.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Album and playlist heroes prefer their own cover, but a CDN resize may
 * fail even when the original URL or a song thumbnail is valid.
 * Avoid identical requests and keep fallback order deterministic.
 */
internal fun detailArtworkCandidates(
    thumbnailUrl: String?,
    fallbackThumbnailUrl: String? = null,
    fallbackThumbnailUrls: List<String> = emptyList(),
    minimumPixels: Int = 960,
): List<String> = (listOfNotNull(thumbnailUrl, fallbackThumbnailUrl) +
    fallbackThumbnailUrls.take(4))
    .filter(String::isNotBlank)
    .distinct()
    .flatMap { url -> listOfNotNull(capsuleArtworkUrl(url, minimumPixels), url) }
    .distinct()

/**
 * One image request at a time, with bounded alternatives and recovery.
 *
 * Previously a 404/403, a slow CDN request, or an invalid oversized URL
 * could strand the hero on a full-screen StandardChrome.panel placeholder.
 * The placeholder also differed from the *page* and toolbar background.
 *
 * Match the page surface even while loading. On an error try the original URL
 * and then up to four real track covers; on a stalled request advance after
 * seven seconds. At most two further rounds are attempted. No polling or
 * continuous retry after exhaustion; Coil keeps its shared image cache.
 */
@Composable
internal fun DetailPortraitImage(
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    fallbackThumbnailUrl: String? = null,
    fallbackThumbnailUrls: List<String> = emptyList(),
    minimumPixels: Int = 960,
    background: Color = StandardChrome.background,
) {
    val candidates = remember(thumbnailUrl, fallbackThumbnailUrl, fallbackThumbnailUrls, minimumPixels) {
        detailArtworkCandidates(thumbnailUrl, fallbackThumbnailUrl, fallbackThumbnailUrls, minimumPixels)
    }
    val coverIdentity = thumbnailUrl?.takeIf(String::isNotBlank)
        ?: fallbackThumbnailUrl?.takeIf(String::isNotBlank)
        ?: fallbackThumbnailUrls.firstOrNull(String::isNotBlank)
    val request = remember(coverIdentity, minimumPixels) { DetailArtworkRequestState(candidates) }
    SideEffect { request.updateCandidates(candidates) }
    val attempt = request.attempt
    LaunchedEffect(request, attempt, request.loaded) {
        if (attempt != null && !request.loaded) {
            delay(7_000L)
            request.fail(attempt)
        }
    }

    Box(modifier.background(background), contentAlignment = Alignment.Center) {
        if (attempt == null) {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                tint = StandardChrome.muted.copy(alpha = 0.38f),
                modifier = Modifier.size(56.dp),
            )
        } else {
            // Preserve the same source after success even if track pagination
            // changes fallback order. Round also makes same-URL retries real.
            key(coverIdentity, attempt) {
                AsyncImage(
                    model = attempt.source,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = { request.succeed(attempt) },
                    onError = { request.fail(attempt) },
                )
            }
        }
    }
}

@Composable
internal fun AlbumArtwork(
    thumbnailUrl: String?,
    background: Color,
    modifier: Modifier = Modifier,
    artworkAlpha: () -> Float = { 1f },
    artworkMotion: DetailHeaderMotionState? = null,
    fallbackThumbnailUrl: String? = null,
    fallbackThumbnailUrls: List<String> = emptyList(),
    title: String = "",
) {
    AlbumPortraitFrame(
        background = background,
        modifier = modifier,
        artworkAlpha = artworkAlpha,
        artworkMotion = artworkMotion,
        caption = { CapsuleDetailCoverTitle(title, artworkMotion) },
    ) {
        DetailPortraitImage(
            thumbnailUrl = thumbnailUrl,
            fallbackThumbnailUrl = fallbackThumbnailUrl,
            fallbackThumbnailUrls = fallbackThumbnailUrls,
            background = background,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Keep the established portrait height and capsule baseline on short and tall screens. */
internal fun albumCoverHeight(width: Dp, screenHeight: Dp): Dp {
    val natural = width / 0.85f
    return minOf(natural, maxOf(natural * 0.83f, screenHeight * 0.50f)) + 20.dp
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AlbumArtworkLayers(
    painter: Painter,
    blurred: ImageBitmap?,
    background: Color,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier,
    artworkAlpha: () -> Float = { 1f },
    artworkMotion: DetailHeaderMotionState? = null,
) {
    AlbumPortraitFrame(
        background = background,
        modifier = modifier,
        artworkAlpha = artworkAlpha,
        artworkMotion = artworkMotion,
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(imageModifier),
        )
    }
}
