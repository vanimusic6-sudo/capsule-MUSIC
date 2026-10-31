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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.material3.Icon
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
    artworkMotion: DetailHeaderMotionState? = null,
) {
    val candidates = remember(thumbnailUrl, fallbackThumbnailUrl, fallbackThumbnailUrls, minimumPixels) {
        detailArtworkCandidates(thumbnailUrl, fallbackThumbnailUrl, fallbackThumbnailUrls, minimumPixels)
    }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    var retryRound by remember(candidates) { mutableIntStateOf(0) }
    var loaded by remember(candidates) { mutableStateOf(false) }
    var exhausted by remember(candidates) { mutableStateOf(false) }
    // Coil may already have resolved a cached cover before a refreshed motion
    // controller is attached. Re-publish SUCCESS only; reset belongs to the
    // parent album/playlist identity, not each individual fallback tile.
    LaunchedEffect(loaded, artworkMotion) {
        if (loaded) artworkMotion?.setArtworkReady(true)
    }
    fun nextCandidate() {
        if (loaded || exhausted) return
        if (candidateIndex < candidates.lastIndex) {
            candidateIndex++
        } else if (retryRound < 2) {
            retryRound++
            candidateIndex = 0
        } else {
            exhausted = true
        }
    }
    LaunchedEffect(candidates, candidateIndex, retryRound, loaded, exhausted) {
        if (candidates.isNotEmpty() && !loaded && !exhausted) {
            delay(7_000L)
            if (!loaded && !exhausted) nextCandidate()
        }
    }

    Box(modifier.background(background), contentAlignment = Alignment.Center) {
        if (candidates.isEmpty() || exhausted) {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                tint = StandardChrome.muted.copy(alpha = 0.38f),
                modifier = Modifier.size(56.dp),
            )
        }
        if (candidates.isNotEmpty() && !exhausted) {
            val index = candidateIndex.coerceIn(0, candidates.lastIndex)
            val source = candidates[index]
            // Recreate the loader only on source/round change, so a retry of
            // the *same* URL really issues another request rather than
            // remaining in Coil's previous terminal Error state.
            key(source, retryRound) {
                AsyncImage(
                    model = source,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = {
                        loaded = true
                        // This callback was missing from the ready-state path:
                        // artworkReveal stayed at ZERO and obscured even a
                        // successfully loaded bitmap with the grey page matte.
                        artworkMotion?.setArtworkReady(true)
                    },
                    onError = {
                        if (!loaded && candidateIndex == index) nextCandidate()
                    },
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
) {
    // Only a NEW main cover resets the shared arrival state; additional
    // fallback URLs appearing during pagination must never blank an already
    // visible image or reset the pinned toolbar.
    LaunchedEffect(thumbnailUrl, artworkMotion) {
        artworkMotion?.setArtworkReady(false)
    }
    AlbumPortraitFrame(
        background = background,
        modifier = modifier,
        artworkAlpha = artworkAlpha,
        artworkMotion = artworkMotion,
    ) {
        DetailPortraitImage(
            thumbnailUrl = thumbnailUrl,
            fallbackThumbnailUrl = fallbackThumbnailUrl,
            fallbackThumbnailUrls = fallbackThumbnailUrls,
            background = background,
            artworkMotion = artworkMotion,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Recover the 44dp formerly lost to an EXTERNAL spacer above the artwork.
 * Put those pixels into the portrait itself instead: album and playlist art
 * reach the screen's top edge, while the capsule remains at its old baseline.
 */
internal fun albumCoverHeight(width: Dp, screenHeight: Dp): Dp {
    val natural = width / 0.85f
    // Keep the portrait under the pinned bar, but no longer reserve the
    // historical 44dp phantom top ribbon: it pushed album and playlist
    // controls down long after the artwork was already opaque.
    return minOf(natural, maxOf(natural * 0.83f, screenHeight * 0.50f)) + 12.dp + 8.dp
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
    // A predecoded Painter has no Coil onSuccess callback. Mark it ready
    // immediately, otherwise it is hidden behind artworkReveal=0 forever.
    LaunchedEffect(painter, artworkMotion) {
        artworkMotion?.setArtworkReady(true)
    }
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
