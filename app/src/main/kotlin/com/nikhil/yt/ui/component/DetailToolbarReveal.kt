package com.nikhil.yt.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * One scroll-driven photo / pinned-toolbar transition for albums and all
 * playlists. Titles live ONLY in the pinned top bar, as on ArtistScreen.
 *
 * Measure the actual artwork's resting bottom edge, including any artwork
 * inset, rather than guessing the title/caption height or assuming one
 * device size. Drawing samples scroll state without a per-frame animation.
 * Artist, album and playlist pages share the same fade function.
 */
internal class DetailHeaderMotionState(
    private val scroll: LazyListState,
    private val density: Density,
) {
    var hasHeader: Boolean = true

    // Cover readiness comes from the *real* Coil success callback, not from
    // having a non-empty metadata URL. Both the artwork and pinned bar use
    // the same short reveal. No independent toolbar colour flash on load.
    var isArtworkReady by mutableFloatStateOf(0f)
        private set
    var artworkReveal by mutableFloatStateOf(0f)
        private set

    fun setArtworkReady(ready: Boolean) {
        val value = if (ready) 1f else 0f
        if (isArtworkReady != value) isArtworkReady = value
    }

    fun updateArtworkReveal(value: Float) {
        val bounded = value.coerceIn(0f, 1f)
        if (artworkReveal != bounded) artworkReveal = bounded
    }

    private var restArtworkBottomPx by mutableFloatStateOf(Float.NaN)
    private var toolbarBottomPx by mutableFloatStateOf(Float.NaN)
    private var toolbarHeightPx by mutableFloatStateOf(Float.NaN)

    fun measureArtwork(coordinates: LayoutCoordinates) {
        if (scroll.firstVisibleItemIndex != 0) return
        val bottom = coordinates.positionInRoot().y +
            coordinates.size.height + scroll.firstVisibleItemScrollOffset
        if (bottom.isFinite() &&
            (restArtworkBottomPx.isNaN() || kotlin.math.abs(bottom - restArtworkBottomPx) > 0.5f)
        ) {
            restArtworkBottomPx = bottom
        }
    }

    fun measureToolbar(coordinates: LayoutCoordinates) {
        val height = coordinates.size.height.toFloat()
        val bottom = coordinates.positionInRoot().y + height
        if (bottom.isFinite() &&
            (toolbarBottomPx.isNaN() || kotlin.math.abs(bottom - toolbarBottomPx) > 0.5f)
        ) {
            toolbarBottomPx = bottom
        }
        if (height > 0f && (toolbarHeightPx.isNaN() ||
            kotlin.math.abs(height - toolbarHeightPx) > 0.5f)
        ) {
            toolbarHeightPx = height
        }
    }

    fun artworkFade(): Float {
        if (!hasHeader) return 1f
        val viewportWidth = scroll.layoutInfo.viewportSize.width
        if (viewportWidth <= 0) return 0f
        // Use actual measured photo + toolbar edges. Only before the first
        // measure, use the artist's reference size as a conservative fallback.
        // Album and playlist photographs may be taller, and using that artist
        // height after measurement used to leave a large black gap above
        // the collection controls while the photo was still offscreen.
        val fallbackArtistBottomPx = with(density) {
            artistHeroArtworkHeight(viewportWidth.toDp()).roundToPx().toFloat()
        }
        return detailArtistLengthArtworkFade(
            firstVisibleItemIndex = scroll.firstVisibleItemIndex,
            offsetPx = scroll.firstVisibleItemScrollOffset,
            artistArtworkBottomPx = fallbackArtistBottomPx,
            detailArtworkBottomPx = restArtworkBottomPx,
            toolbarBottomPx = toolbarBottomPx,
            toolbarHeightPx = toolbarHeightPx,
        )
    }

    fun artworkAlpha(): Float = (1f - artworkFade()) * artworkReveal
}

/**
 * Compute the transition from the real bottom edge of THIS album or playlist
 * photograph, not an artist portrait with a different height. In the first
 * layout frame (before onGloballyPositioned), use the artist size as fallback.
 * This avoids a premature fully black strip with taller collection covers.
 */
internal fun detailArtistLengthArtworkFade(
    firstVisibleItemIndex: Int,
    offsetPx: Int,
    artistArtworkBottomPx: Float,
    detailArtworkBottomPx: Float,
    toolbarBottomPx: Float,
    toolbarHeightPx: Float = toolbarBottomPx,
): Float {
    if (firstVisibleItemIndex > 0) return 1f
    if (!toolbarBottomPx.isFinite()) return 0f
    val actualBottom = if (detailArtworkBottomPx.isFinite())
        detailArtworkBottomPx else artistArtworkBottomPx
    return detailArtworkFadeLikeArtist(
        firstVisibleItemIndex, offsetPx, actualBottom, toolbarBottomPx, toolbarHeightPx,
    )
}

/** Shared artist-style falloff for a measured artwork-bottom geometry. */
internal fun detailArtworkFadeLikeArtist(
    firstVisibleItemIndex: Int,
    offsetPx: Int,
    artworkBottomPx: Float,
    toolbarBottomPx: Float,
    toolbarHeightPx: Float = toolbarBottomPx,
): Float {
    if (firstVisibleItemIndex > 0) return 1f
    if (!artworkBottomPx.isFinite() || !toolbarBottomPx.isFinite()) return 0f
    val distance = (artworkBottomPx - toolbarBottomPx).coerceAtLeast(1f)
    return portraitDissolveFraction(offsetPx.toFloat(), distance, toolbarHeightPx)
}

/**
 * A long, low-contrast photo dissolve with a precisely measured endpoint.
 * Start at the original 12% point of photo-to-toolbar travel so the artwork
 * gradually loses contrast while scrolling; finish at the SAME physical
 * bottom-edge crossing as before (one raster pixel inside the pinned bar).
 * This avoids both the late sudden fade and a prematurely solid black strip.
 *
 * Album/playlist geometry still uses its own measured image bottom and real
 * toolbar size. No frame timers or new animations are scheduled.
 */
internal fun portraitDissolveFraction(
    offsetPx: Float,
    distancePx: Float,
    toolbarHeightPx: Float,
): Float {
    val distance = distancePx.coerceAtLeast(1f)
    // End remains tied to the actual edge of the pinned bar, not a
    // percentage, so the toolbar never fills over the buttons.
    val end = (distance - 1f).coerceAtLeast(1f)
    // Start at the old 12% point; on exceptionally short pages, never
    // start later than one actual measured toolbar height from the edge.
    // This keeps the measured bar inside the already-started fade window.
    val measuredBar = toolbarHeightPx.takeIf { it.isFinite() && it > 0f } ?: end
    val latestStart = (end - measuredBar).coerceAtLeast(0f)
    val start = minOf(distance * 0.12f, latestStart)
        .coerceIn(0f, (end - 1f).coerceAtLeast(0f))
    val fraction = ((offsetPx - start) / (end - start).coerceAtLeast(1f)).coerceIn(0f, 1f)
    // Evaluate the polynomial in Double: Float32 cancellation near t=1
    // caused the last frames to wiggle backward (and fail monotonic tests).
    // The returned draw alpha is still Float and stays strictly in [0, 1].
    val t = fraction.toDouble()
    return (t * t * t * (t * (t * 6.0 - 15.0) + 10.0)).toFloat().coerceIn(0f, 1f)
}

@Composable
internal fun rememberDetailHeaderMotion(
    state: LazyListState,
    hasHeader: Boolean,
): DetailHeaderMotionState {
    val density = LocalDensity.current
    val motion = remember(state, density) { DetailHeaderMotionState(state, density) }
    // Load completion is a one-off transition; it does not run while scrolling
    // or poll the image provider. The page and toolbar acquire their colour
    // from the same alpha to avoid different-looking placeholders.
    val reveal by animateFloatAsState(
        targetValue = motion.isArtworkReady,
        animationSpec = tween(durationMillis = 240),
        label = "detail-cover-arrival",
    )
    SideEffect {
        motion.hasHeader = hasHeader
        motion.updateArtworkReveal(reveal)
    }
    return motion
}

/** Reuse the EXACT artist toolbar surface policy: never double-tint the photo. */
internal fun detailToolbarBackdropAlpha(
    photoDissolve: Float,
    forceOpaque: Boolean = false,
): Float = if (forceOpaque) 1f else artistToolbarSurfaceAlpha(photoDissolve)

/** The photo fades in the hero, and only then the bar paints the page surface. */
internal fun Modifier.artistStyleDetailBackdrop(
    motion: DetailHeaderMotionState,
    surfaceColor: Color,
    forceOpaque: Boolean = false,
): Modifier = drawBehind {
    drawRect(surfaceColor.copy(alpha = maxOf(
        detailToolbarBackdropAlpha(
            photoDissolve = motion.artworkFade(),
            forceOpaque = forceOpaque,
        ),
        1f - motion.artworkReveal,
    )))
}
