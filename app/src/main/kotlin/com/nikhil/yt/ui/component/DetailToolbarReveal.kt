package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Passive album/playlist title and photograph observer.
 * No magnet, no animation spring, no title overlay and no fling interception:
 * the list owns scrolling with its ordinary Capsule inertia.
 */
internal class DetailHeaderMotionState(
    private val scroll: LazyListState,
    private val density: Density,
    private val headerPresence: State<Boolean> = androidx.compose.runtime.mutableStateOf(true),
) {
    private var restArtworkBottomPx by mutableFloatStateOf(Float.NaN)
    private var toolbarBottomPx by mutableFloatStateOf(Float.NaN)
    private var toolbarHeightPx by mutableFloatStateOf(Float.NaN)
    private var coverTitleTopPx by mutableFloatStateOf(Float.NaN)
    private var coverTitleHeightPx by mutableFloatStateOf(Float.NaN)

    fun measureArtwork(coordinates: LayoutCoordinates) {
        if (scroll.firstVisibleItemIndex != 0) return
        val bottom = coordinates.positionInRoot().y +
            coordinates.size.height + scroll.firstVisibleItemScrollOffset
        if (bottom.isFinite() && (!restArtworkBottomPx.isFinite() ||
                kotlin.math.abs(bottom - restArtworkBottomPx) > .5f)) {
            restArtworkBottomPx = bottom
        }
    }

    fun measureToolbar(coordinates: LayoutCoordinates) {
        toolbarBottomPx = coordinates.positionInRoot().y + coordinates.size.height
        toolbarHeightPx = coordinates.size.height.toFloat()
    }

    fun measureCoverTitle(coordinates: LayoutCoordinates) {
        if (scroll.firstVisibleItemIndex != 0) return
        // Store the unscrolled cover caption bounds. All future updates use
        // list scroll offset, not a second motion calculation.
        val top = coordinates.positionInRoot().y + scroll.firstVisibleItemScrollOffset
        if (top.isFinite()) coverTitleTopPx = top
        if (coordinates.size.height > 0) coverTitleHeightPx = coordinates.size.height.toFloat()
    }

    private val photoProgress by derivedStateOf {
        if (!headerPresence.value || scroll.firstVisibleItemIndex > 0) 1f
        else {
            val fallback = if (restArtworkBottomPx.isFinite()) 0f else with(density) {
                artistHeroArtworkHeight(scroll.layoutInfo.viewportSize.width.toDp()).toPx()
            }
            detailArtistLengthArtworkFade(
                scroll.firstVisibleItemIndex,
                scroll.firstVisibleItemScrollOffset,
                fallback, restArtworkBottomPx, toolbarBottomPx, toolbarHeightPx,
            )
        }
    }

    private val titleOcclusion by derivedStateOf {
        detailTitleOcclusionFraction(
            scroll.firstVisibleItemIndex,
            scroll.firstVisibleItemScrollOffset.toFloat(),
            coverTitleTopPx,
            coverTitleHeightPx,
            toolbarBottomPx,
            headerPresence.value,
        )
    }

    fun artworkFade(): Float = photoProgress
    fun artworkAlpha(): Float = 1f - photoProgress
    fun toolbarTitleAlpha(): Float = titleOcclusion
    fun coverTitleAlpha(): Float = 1f - titleOcclusion
    fun hairlineReveal(): Float = photoProgress
}

/**
 * When the toolbar covers the caption, softly crossfade the caption and the
 * separate compact title. Pure draw-time scroll geometry; no clock or delay.
 * A two-line cover title naturally gets a longer fade than a one-line title.
 */
internal fun detailTitleOcclusionFraction(
    firstVisibleItemIndex: Int,
    scrollOffsetPx: Float,
    coverTitleTopPx: Float,
    coverTitleHeightPx: Float,
    toolbarBottomPx: Float,
    hasHero: Boolean = true,
): Float {
    if (!hasHero || firstVisibleItemIndex > 0) return 1f
    if (!scrollOffsetPx.isFinite() || !coverTitleTopPx.isFinite() ||
        !coverTitleHeightPx.isFinite() || coverTitleHeightPx <= 0f ||
        !toolbarBottomPx.isFinite()) return 0f
    val coveredPx = toolbarBottomPx - (coverTitleTopPx - scrollOffsetPx)
    val t = (coveredPx / coverTitleHeightPx).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The original artwork fade remains independent of the title. */
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
    contentIdentity: Any? = null,
): DetailHeaderMotionState {
    val density = LocalDensity.current
    val headerPresence = rememberUpdatedState(hasHeader)
    return remember(state, density, contentIdentity) {
        DetailHeaderMotionState(state, density, headerPresence)
    }
}

internal fun detailToolbarBackdropAlpha(
    photoDissolve: Float,
    forceOpaque: Boolean = false,
): Float = if (forceOpaque) 1f else artistToolbarSurfaceAlpha(photoDissolve)

internal fun Modifier.artistStyleDetailBackdrop(
    motion: DetailHeaderMotionState,
    surfaceColor: Color,
    forceOpaque: Boolean = false,
): Modifier = drawBehind {
    val alpha = detailToolbarBackdropAlpha(motion.artworkFade(), forceOpaque)
    if (alpha > 0f) drawRect(surfaceColor.copy(alpha = alpha))
}

/** Separate one-line title, invisible until the original passes under the bar. */
@Composable
internal fun DetailToolbarTitle(title: String, motion: DetailHeaderMotionState) {
    val hidden by androidx.compose.runtime.remember(motion) {
        derivedStateOf { motion.toolbarTitleAlpha() <= .01f }
    }
    Text(
        text = title,
        modifier = Modifier
            .graphicsLayer { alpha = motion.toolbarTitleAlpha() }
            .testTag("capsule:detail-toolbar-title")
            .semantics {
                contentDescription = title
                if (hidden) hideFromAccessibility()
            },
        style = MaterialTheme.typography.headlineLarge.copy(
            fontSize = 22.sp,
            lineHeight = (29f * 22f / 25f).sp,
            letterSpacing = 0.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun detailTitleStyle() = MaterialTheme.typography.headlineLarge.copy(
    fontSize = 25.sp,
    lineHeight = 29.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.sp,
    textAlign = TextAlign.Center,
)

/** Pinned navigation only: never paints an overlay or animates the caption. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailTopAppBar(
    motion: DetailHeaderMotionState,
    surfaceColor: Color,
    forceOpaque: Boolean,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val foreground = if (forceOpaque) StandardChrome.text
        else lerp(Color.White, StandardChrome.text, motion.artworkFade())
    TopAppBar(
        modifier = modifier
            .onGloballyPositioned(motion::measureToolbar)
            .artistStyleDetailBackdrop(motion, surfaceColor, forceOpaque)
            .capsuleTopBarHairline(StandardChrome.muted) {
                if (forceOpaque) 1f else motion.hairlineReveal()
            },
        windowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        expandedHeight = ArtistToolbarCompactHeight,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = foreground,
            navigationIconContentColor = foreground,
            actionIconContentColor = foreground,
        ),
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
    )
}
