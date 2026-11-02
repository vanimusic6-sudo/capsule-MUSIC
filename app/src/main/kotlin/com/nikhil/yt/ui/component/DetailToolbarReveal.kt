package com.nikhil.yt.ui.component

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density

/** Shared, measured scroll progress. Image loading never controls drawing opacity. */
internal class DetailHeaderMotionState(
    private val scroll: LazyListState,
    private val density: Density,
    private val headerPresence: State<Boolean> = mutableStateOf(true),
) {
    private var restArtworkBottomPx by mutableFloatStateOf(Float.NaN)
    private var toolbarBottomPx by mutableFloatStateOf(Float.NaN)
    private var toolbarHeightPx by mutableFloatStateOf(Float.NaN)
    private var sourceCenter by mutableStateOf(Offset.Unspecified)
    private var target by mutableStateOf(Rect.Zero)
    var overlayOrigin by mutableStateOf(Offset.Zero)
    var titleAttached by mutableStateOf(false)
    var title by mutableStateOf("")
    var compactTitleScale by mutableFloatStateOf(22f / 25f)
    private var coverLayout by mutableStateOf<TextLayoutResult?>(null)
    var fallbackLayout by mutableStateOf<TextLayoutResult?>(null)

    fun measureCoverTitle(coordinates: LayoutCoordinates) {
        if (scroll.firstVisibleItemIndex != 0) return
        val p = coordinates.positionInRoot()
        sourceCenter = Offset(p.x + coordinates.size.width / 2f,
            p.y + coordinates.size.height / 2f + scroll.firstVisibleItemScrollOffset)
    }

    fun measureCoverTitleText(layout: TextLayoutResult) { coverLayout = layout }
    fun measureToolbarTitle(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        target = Rect(p, androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }

    private fun layout() = coverLayout?.takeIf { it.layoutInput.text.text == title } ?: fallbackLayout
    fun overlayReady() = titleAttached && title.isNotBlank() && layout() != null &&
        target.width > 0f && (sourceCenter.isSpecified || !headerPresence.value)

    fun geometry(): DetailTitleGeometry? {
        if (!overlayReady()) return null
        val layout = layout() ?: return null
        return detailTitleGeometry(progress, compactTitleScale, layout.size.width.toFloat(),
            layout.size.height.toFloat(), layout.getLineLeft(0), layout.firstBaseline,
            layout.getLineBottom(0) - layout.getLineTop(0),
            if (sourceCenter.isSpecified) sourceCenter else target.center,
            scroll.firstVisibleItemScrollOffset.toFloat(), target)
    }

    /**
     * One opaque paragraph follows the header all the way into the toolbar.
     * Only glyphs at a genuinely clipped edge receive an alpha mask: the first
     * line stays solid except for a short continuation hint at its final glyphs.
     *
     * Important: DstIn is applied to the entire isolated text layer. Masking
     * only a tiny strip at the end left a hard-scissored boundary, and the
     * previous "second line hidden" condition never softened the bottom edge.
     */
    fun drawTitle(scope: DrawScope, color: Color, paint: Paint, featherPx: Float) = with(scope) {
        val geometry = geometry() ?: return@with
        val layout = layout() ?: return@with
        val clip = geometry.clip.translate(-overlayOrigin)
        val origin = geometry.origin - overlayOrigin
        if (clip.width <= 1f || clip.height <= 1f || !clip.width.isFinite() ||
            !clip.height.isFinite()) return@with

        val titleScale = geometry.scale
        // Calculate horizontal overflow from lines ACTUALLY intersecting the
        // viewport. An already-disappeared second line cannot dim the first.
        val visibleLines = (0 until layout.lineCount).filter { line ->
            origin.y + layout.getLineTop(line) * titleScale < clip.bottom &&
                origin.y + layout.getLineBottom(line) * titleScale > clip.top
        }
        val right = visibleLines.maxOfOrNull { origin.x + layout.getLineRight(it) * titleScale }
            ?: clip.right
        val left = visibleLines.minOfOrNull { origin.x + layout.getLineLeft(it) * titleScale }
            ?: clip.left
        val horizontalFeather = minOf(featherPx, clip.width * .27f).coerceAtLeast(1f)
        val rightFade = detailTitleFeatherStrength(right - clip.right, horizontalFeather)
        val leftFade = detailTitleFeatherStrength(clip.left - left, horizontalFeather)

        // A two-line headline should disappear by its TRAILING line, rather
        // than slicing a row of pixels off as the title enters the one-line
        // top bar. Keep the FIRST line at full opacity throughout the handoff.
        val lastLine = layout.lineCount - 1
        val lastLineTop = if (lastLine > 0) {
            origin.y + layout.getLineTop(lastLine) * titleScale
        } else Float.POSITIVE_INFINITY
        val lastLineBottom = if (lastLine > 0) {
            origin.y + layout.getLineBottom(lastLine) * titleScale
        } else Float.NEGATIVE_INFINITY
        val firstLineBottom = origin.y + layout.getLineBottom(0) * titleScale
        val lowerLineVisible = lastLine > 0 && lastLineTop < clip.bottom &&
            lastLineBottom > clip.top && firstLineBottom < clip.bottom
        val trailingFeather = if (lowerLineVisible) {
            minOf(featherPx, (clip.bottom - maxOf(firstLineBottom, lastLineTop)).coerceAtLeast(0f))
        } else 0f
        val lowerFade = if (trailingFeather > 0f) {
            detailTitleFeatherStrength(
                lastLineBottom - clip.bottom,
                (lastLineBottom - lastLineTop).coerceAtLeast(1f),
            )
        } else 0f

        // Once the second line has folded away, the first line is still
        // logically truncated EVEN IF it physically fits the top-bar width.
        // Preserve a 30dp end-of-line continuation feather. The old 18dp
        // strip tried to do this but was cropped by an opaque hard edge.
        val continuationFade = if (lastLine > 0) {
            detailTitleFeatherStrength(
                lastLineBottom - clip.bottom,
                (lastLineBottom - lastLineTop).coerceAtLeast(1f),
            )
        } else 0f
        val activeRightFeather = horizontalFeather * maxOf(rightFade, continuationFade)
        val firstLineTail = origin.x + layout.getLineRight(0) * titleScale
        val rightFadeEnd = if (rightFade > 0f) clip.right else
            minOf(clip.right, firstLineTail)

        val needsMask = activeRightFeather > 0f || leftFade > 0f || lowerFade > 0f
        if (needsMask) drawContext.canvas.saveLayer(clip, paint)
        clipRect(clip.left, clip.top, clip.right, clip.bottom) {
            withTransform({
                translate(origin.x, origin.y)
                scale(titleScale, titleScale, Offset.Zero)
            }) {
                drawText(layout, color = color, alpha = 1f)
            }
            if (needsMask) {
                // Full-area masks: the shader stays opaque until the last
                // few pixels, then feathers INTO the clip boundary. Unlike
                // painting a clipped strip, there is no razor-sharp seam.
                val region = androidx.compose.ui.geometry.Size(clip.width, clip.height)
                val position = Offset(clip.left, clip.top)
                if (activeRightFeather > 0f) {
                    // Fully transparent at the actual text/viewport edge.
                    // For a hidden second line the edge is the last real
                    // first-line glyph, not an unrelated rectangle boundary.
                    val endX = rightFadeEnd - clip.left
                    val activeFeather = activeRightFeather.coerceAtLeast(.01f)
                    val mask = Brush.horizontalGradient(
                        listOf(Color.White, Color.Transparent),
                        startX = endX - activeFeather,
                        endX = endX,
                    )
                    if (rightFade > 0f) {
                        drawRect(mask, topLeft = position, size = region,
                            blendMode = BlendMode.DstIn)
                    } else {
                        // A second-line continuation hint must never erase
                        // the other line horizontally before its own bottom
                        // feather completes.
                        clipRect(clip.left, clip.top, clip.right,
                            minOf(clip.bottom, firstLineBottom)) {
                            drawRect(mask, topLeft = position, size = region,
                                blendMode = BlendMode.DstIn)
                        }
                    }
                }
                if (leftFade > 0f) {
                    val activeFeather = (horizontalFeather * leftFade).coerceAtLeast(.01f)
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White),
                            startX = 0f, endX = activeFeather,
                        ), topLeft = position, size = region, blendMode = BlendMode.DstIn,
                    )
                }
                if (lowerFade > 0f) {
                    val activeFeather = (trailingFeather * lowerFade).coerceAtLeast(.01f)
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.White, Color.Transparent),
                            startY = clip.height - activeFeather,
                            endY = clip.height,
                        ), topLeft = position, size = region, blendMode = BlendMode.DstIn,
                    )
                }
            }
        }
        if (needsMask) drawContext.canvas.restore()
    }

    fun measureArtwork(coordinates: LayoutCoordinates) {
        if (scroll.firstVisibleItemIndex != 0) return
        val bottom = coordinates.positionInRoot().y + coordinates.size.height + scroll.firstVisibleItemScrollOffset
        if (bottom.isFinite() && (!restArtworkBottomPx.isFinite() || kotlin.math.abs(bottom - restArtworkBottomPx) > .5f))
            restArtworkBottomPx = bottom
    }
    fun measureToolbar(coordinates: LayoutCoordinates) {
        toolbarHeightPx = coordinates.size.height.toFloat()
        toolbarBottomPx = coordinates.positionInRoot().y + coordinates.size.height
    }
    private val progress by derivedStateOf {
        if (!headerPresence.value || scroll.firstVisibleItemIndex > 0) 1f else {
            val fallback = if (restArtworkBottomPx.isFinite()) 0f else with(density) {
                artistHeroArtworkHeight(scroll.layoutInfo.viewportSize.width.toDp()).toPx()
            }
            detailArtistLengthArtworkFade(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset,
                fallback, restArtworkBottomPx, toolbarBottomPx, toolbarHeightPx)
        }
    }
    fun artworkFade() = progress
    // Keep the original photograph: only the static matte and pinned bar own backgrounds.
    // The single moving title is opaque, but the PHOTO retains its original measured scroll dissolve.
    fun artworkAlpha() = 1f - progress
    fun toolbarTitleAlpha() = progress
    fun compactTitleAlpha() = if (progress >= 1f) 1f else 0f
    fun coverTitleAlpha() = 1f - compactTitleAlpha()
}

/**
 * Smoothly GROW the feather from zero width as ink crosses the boundary.
 * The alpha at the edge is always zero; reducing only the endpoint alpha
 * left conspicuously half-visible chopped glyphs on moderately long titles.
 * This is reversible scroll geometry, not a clock-based opacity animation.
 */
internal fun detailTitleFeatherStrength(overlapPx: Float, featherPx: Float): Float {
    if (!overlapPx.isFinite() || overlapPx <= 0f) return 0f
    val t = (overlapPx / featherPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal data class DetailTitleGeometry(val origin: Offset, val scale: Float, val clip: Rect)

/** One fixed paragraph, one baseline trajectory and a shrinking viewport; no reflow/ellipsis swap. */
internal fun detailTitleGeometry(
    progress: Float, compactScale: Float, width: Float, height: Float,
    lineLeft: Float, baseline: Float, lineHeight: Float,
    sourceCenter: Offset, scrollOffset: Float, target: Rect,
): DetailTitleGeometry {
    val p = progress.coerceIn(0f, 1f)
    fun mix(a: Float, b: Float) = a + (b - a) * p
    val scale = mix(1f, compactScale)
    val source = Offset(sourceCenter.x - width / 2f, sourceCenter.y - height / 2f - scrollOffset)
    val glyphX = mix(source.x + lineLeft, target.left)
    val targetBaseline = target.center.y - lineHeight * compactScale / 2f + baseline * compactScale
    val glyphY = mix(source.y + baseline, targetBaseline)
    return DetailTitleGeometry(
        Offset(glyphX - lineLeft * scale, glyphY - baseline * scale), scale,
        Rect(mix(source.x, target.left), mix(source.y, target.top),
            mix(source.x + width, target.right), mix(source.y + height, target.bottom)),
    )
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
    // Read the current caller state in the same composition. A post-compose
    // SideEffect left one frame using "no header" and flashed the pinned name.
    val headerPresence = rememberUpdatedState(hasHeader)
    return remember(state, density, contentIdentity) {
        DetailHeaderMotionState(state, density, headerPresence)
    }
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

@Composable
internal fun DetailToolbarTitle(title: String, motion: DetailHeaderMotionState) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = detailTitleStyle()
    val sourceWidth = with(density) { (LocalConfiguration.current.screenWidthDp.dp - 56.dp).roundToPx().coerceAtLeast(1) }
    val fallback = remember(title, style, sourceWidth, measurer) {
        measurer.measure(title, style, maxLines = 2, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = sourceWidth))
    }
    SideEffect {
        motion.title = title
        motion.fallbackLayout = fallback
        motion.compactTitleScale = with(density) { 22.sp.toPx() / 25.sp.toPx() }
    }
    DisposableEffect(motion) {
        motion.titleAttached = true
        onDispose { motion.titleAttached = false }
    }
    val hidden by remember(motion) { derivedStateOf { motion.compactTitleAlpha() == 0f } }
    Box(Modifier.fillMaxWidth().height(with(density) { (29f * 22f / 25f).sp.toDp() })
        .onGloballyPositioned(motion::measureToolbarTitle)
        .testTag("capsule:detail-toolbar-title")
        .semantics { contentDescription = title; if (hidden) hideFromAccessibility() })
}

@Composable
internal fun detailTitleStyle() = MaterialTheme.typography.headlineLarge.copy(
    fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.sp, textAlign = TextAlign.Center,
)

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
    Box(modifier.fillMaxSize().onGloballyPositioned { motion.overlayOrigin = it.positionInRoot() }) {
    TopAppBar(
        modifier = Modifier.onGloballyPositioned(motion::measureToolbar)
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
    val titleColor = StandardChrome.text
    Spacer(Modifier.matchParentSize().drawWithCache {
        val paint = Paint()
        // 30dp only on overhanging glyphs: gentle static toolbar truncation
        // without dimming the intact headline or its photographic backdrop.
        val feather = 30.dp.toPx()
        onDrawBehind { motion.drawTitle(this, titleColor, paint, feather) }
    })
    }

}
