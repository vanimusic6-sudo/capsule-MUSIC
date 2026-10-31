package com.nikhil.yt.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density

/** Shared, measured scroll progress. Image loading never controls drawing opacity. */
internal class DetailHeaderMotionState(
    private val scroll: LazyListState,
    private val density: Density,
    initialHasHeader: Boolean = true,
) {
    var hasHeader by mutableStateOf(initialHasHeader)

    private var restArtworkBottomPx by mutableFloatStateOf(Float.NaN)
    private var toolbarBottomPx by mutableFloatStateOf(Float.NaN)
    private var toolbarHeightPx by mutableFloatStateOf(Float.NaN)
    private var restTitleX by mutableFloatStateOf(Float.NaN)
    private var restTitleY by mutableFloatStateOf(Float.NaN)
    private var compactTitleX by mutableFloatStateOf(Float.NaN)
    private var compactTitleY by mutableFloatStateOf(Float.NaN)
    var compactTitleScale by mutableFloatStateOf(22f / 25f)

    // Measure the stationary parent, never the translated text itself. Adding
    // scroll back gives an invariant source anchor and avoids feedback drift.
    fun measureCoverTitle(coordinates: LayoutCoordinates) {
        if (scroll.firstVisibleItemIndex != 0) return
        val position = coordinates.positionInRoot()
        val x = position.x + coordinates.size.width / 2f
        val y = position.y + coordinates.size.height / 2f + scroll.firstVisibleItemScrollOffset
        if (x.isFinite() && restTitleX != x) restTitleX = x
        if (y.isFinite() && (!restTitleY.isFinite() || kotlin.math.abs(y - restTitleY) > .5f)) restTitleY = y
    }

    fun measureToolbarTitle(coordinates: LayoutCoordinates) {
        val position = coordinates.positionInRoot()
        val x = position.x + coordinates.size.width / 2f
        val y = position.y + coordinates.size.height / 2f
        if (x.isFinite() && compactTitleX != x) compactTitleX = x
        if (y.isFinite() && compactTitleY != y) compactTitleY = y
    }

    private fun hasTitleAnchors(): Boolean = restTitleX.isFinite() && restTitleY.isFinite() &&
        compactTitleX.isFinite() && compactTitleY.isFinite()

    fun compactTitleAlpha(): Float = if (hasHeader && hasTitleAnchors())
        detailTitleHandoff(progress) else progress

    fun moveCoverTitle(layer: GraphicsLayerScope) {
        val p = progress
        if (!hasTitleAnchors()) {
            layer.alpha = 1f - p
            return
        }
        // Follow the current source position into the fixed toolbar anchor.
        // Geometry remains scroll-driven and reverses immediately with the finger.
        layer.translationX = (compactTitleX - restTitleX) * p
        layer.translationY = (compactTitleY - restTitleY + scroll.firstVisibleItemScrollOffset) * p
        val scale = 1f + (compactTitleScale - 1f) * p
        layer.scaleX = scale
        layer.scaleY = scale
        layer.alpha = 1f - detailTitleHandoff(p)
    }

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

    // All layers read this cached value. At rest there is no clock or scheduled work.
    private val progress by derivedStateOf {
        if (!hasHeader || scroll.firstVisibleItemIndex > 0) 1f else {
            val fallbackBottom = if (restArtworkBottomPx.isFinite()) 0f else {
                val viewportWidth = scroll.layoutInfo.viewportSize.width
                with(density) { artistHeroArtworkHeight(viewportWidth.toDp()).toPx() }
            }
            detailArtistLengthArtworkFade(
                firstVisibleItemIndex = scroll.firstVisibleItemIndex,
                offsetPx = scroll.firstVisibleItemScrollOffset,
                artistArtworkBottomPx = fallbackBottom,
                detailArtworkBottomPx = restArtworkBottomPx,
                toolbarBottomPx = toolbarBottomPx,
                toolbarHeightPx = toolbarHeightPx,
            )
        }
    }

    fun artworkFade(): Float = progress

    // Cover pixels must never depend on asynchronous readiness. Coil paints a
    // placeholder until decoding finishes, then the real bitmap. A late
    // readiness reset previously multiplied a loaded image by zero forever.
    // Scroll fade is exclusively controlled by the measured scroll geometry.
    fun artworkAlpha(): Float = 1f - progress

    // Once the hero name has scrolled away, the pinned compact title takes over.
    fun toolbarTitleAlpha(): Float = progress
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
    contentIdentity: Any? = null,
): DetailHeaderMotionState {
    val density = LocalDensity.current
    val motion = remember(state, density, contentIdentity) {
        DetailHeaderMotionState(state, density, hasHeader)
    }
    SideEffect { motion.hasHeader = hasHeader }
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
    val alpha = detailToolbarBackdropAlpha(motion.artworkFade(), forceOpaque)
    if (alpha > 0f) drawRect(surfaceColor.copy(alpha = alpha))
}

/** Crossfade glyph layouts only once the moving caption reaches the toolbar. */
internal fun detailTitleHandoff(progress: Float): Float {
    if (progress <= .88f) return 0f
    if (progress >= 1f) return 1f
    val t = ((progress - .88f) / .12f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun Modifier.detailCoverTitleMotion(motion: DetailHeaderMotionState?): Modifier =
    graphicsLayer { motion?.moveCoverTitle(this) }

/**
 * Keep the compact text laid out from the first frame. Colour invalidation
 * redraws actual glyphs when scrolling. Do not rely on reviving an initially
 * empty zero-alpha layer after cached-cover or navigation state changes.
 * Only this small text scope observes progress; the collection stays skippable.
 */
@Composable
internal fun DetailToolbarTitle(title: String, motion: DetailHeaderMotionState) {
    val style = MaterialTheme.typography.titleLarge
    val compactScale = (style.fontSize.value / 25f).takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(.5f, 1f) ?: (22f / 25f)
    SideEffect { motion.compactTitleScale = compactScale }
    val foreground = LocalContentColor.current
    Text(
        text = title,
        modifier = Modifier.onGloballyPositioned(motion::measureToolbarTitle)
            .testTag("capsule:detail-toolbar-title"),
        color = foreground.copy(alpha = foreground.alpha * motion.compactTitleAlpha()),
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Isolate scroll-driven foreground recomposition from the collection and song list. */
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
    val foreground = if (forceOpaque) StandardChrome.text else
        lerp(Color.White, StandardChrome.text, motion.artworkFade())
    TopAppBar(
        modifier = modifier.onGloballyPositioned(motion::measureToolbar)
            .artistStyleDetailBackdrop(motion, surfaceColor, forceOpaque),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
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
