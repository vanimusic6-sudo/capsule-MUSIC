package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Second-generation Capsule Light editor.
 *
 * This is intentionally independent from the old order+gap editor. A block owns an absolute
 * vertical position inside a fixed viewport. The solver is the single source of truth for:
 *  - real-element docking,
 *  - empty-cell docking,
 *  - neighbour displacement,
 *  - hard viewport bounds,
 *  - final persisted positions.
 *
 * No visual target is ever shown unless the exact same target can be committed.
 */
internal val CapsuleLightCanvasPositionsBaseEncoded = ""

internal fun decodeCapsuleLightCanvasPositions(raw: String): Map<CapsuleLightBlock, Float> {
    if (raw.isBlank()) return emptyMap()

    val parsed = mutableMapOf<CapsuleLightBlock, Float>()
    raw.split(',').forEach { token ->
        val parts = token.split('=', limit = 2)
        if (parts.size != 2) return@forEach
        val block =
            runCatching { CapsuleLightBlock.valueOf(parts[0].trim()) }
                .getOrNull()
                ?: return@forEach
        val y = parts[1].trim().toFloatOrNull() ?: return@forEach
        if (y.isFinite()) parsed[block] = y.coerceAtLeast(0f)
    }

    return if (parsed.keys.all { it in CapsuleLightBaseOrder }) parsed else emptyMap()
}

internal fun encodeCapsuleLightCanvasPositions(
    positionsDp: Map<CapsuleLightBlock, Float>,
): String =
    CapsuleLightBaseOrder
        .filter { positionsDp.containsKey(it) }
        .joinToString(",") { block ->
            val value = (positionsDp[block] ?: 0f).coerceAtLeast(0f)
            "${block.name}=${"%.2f".format(java.util.Locale.US, value)}"
        }

private val CanvasDockGap = 8.dp
private val CanvasGridStep = 24.dp
private val CanvasRealSnap = 54.dp
private val CanvasPreferredRealSnap = 88.dp
private val CanvasHandleWidth = 54.dp
private val CanvasHandleHeight = 22.dp

private data class CanvasLayout(
    val order: List<CapsuleLightBlock>,
    val topPx: Map<CapsuleLightBlock, Float>,
)

private enum class CanvasTargetKind {
    REAL,
    EMPTY,
    FALLBACK,
}

private data class CanvasPreview(
    val layout: CanvasLayout,
    val kind: CanvasTargetKind,
    val selectedCellTopPx: Float? = null,
)

private fun preferredPair(
    first: CapsuleLightBlock?,
    second: CapsuleLightBlock,
): Boolean {
    if (first == null || first == second) return false
    val pair = setOf(first, second)
    return pair == setOf(CapsuleLightBlock.ARTWORK, CapsuleLightBlock.LYRIC) ||
        pair == setOf(CapsuleLightBlock.ARTWORK, CapsuleLightBlock.METADATA) ||
        pair == setOf(CapsuleLightBlock.LYRIC, CapsuleLightBlock.METADATA) ||
        pair == setOf(CapsuleLightBlock.METADATA, CapsuleLightBlock.PROGRESS) ||
        pair == setOf(CapsuleLightBlock.PROGRESS, CapsuleLightBlock.MODE_SWITCH) ||
        pair == setOf(CapsuleLightBlock.MODE_SWITCH, CapsuleLightBlock.CONTROLS)
}

private fun totalMinimumHeight(
    order: List<CapsuleLightBlock>,
    heightsPx: Map<CapsuleLightBlock, Float>,
    gapPx: Float,
): Float =
    order.sumOf { (heightsPx[it] ?: 0f).toDouble() }.toFloat() +
        gapPx * (order.size - 1).coerceAtLeast(0)

private fun packedLayout(
    order: List<CapsuleLightBlock>,
    heightsPx: Map<CapsuleLightBlock, Float>,
    viewportPx: Float,
    gapPx: Float,
): CanvasLayout {
    if (order.isEmpty()) return CanvasLayout(emptyList(), emptyMap())

    val minHeight = totalMinimumHeight(order, heightsPx, gapPx)
    val actualGap =
        if (order.size <= 1 || minHeight <= viewportPx) {
            gapPx
        } else {
            (
                (viewportPx - order.sumOf { (heightsPx[it] ?: 0f).toDouble() }.toFloat()) /
                    (order.size - 1)
                ).coerceAtLeast(0f)
        }

    val result = mutableMapOf<CapsuleLightBlock, Float>()
    var y = 0f
    order.forEachIndexed { index, block ->
        result[block] = y
        y += heightsPx[block] ?: 0f
        if (index != order.lastIndex) y += actualGap
    }

    return CanvasLayout(order, result)
}

private fun normalizeLayout(
    preferredOrder: List<CapsuleLightBlock>,
    persistedTopPx: Map<CapsuleLightBlock, Float>,
    heightsPx: Map<CapsuleLightBlock, Float>,
    viewportPx: Float,
    gapPx: Float,
): CanvasLayout {
    if (preferredOrder.any { !heightsPx.containsKey(it) }) {
        return CanvasLayout(preferredOrder, emptyMap())
    }

    val minimum = totalMinimumHeight(preferredOrder, heightsPx, gapPx)
    if (minimum > viewportPx + 0.5f) {
        // Artwork sizing is expected to prevent this. The zero-gap fallback still preserves every
        // block's full measured height rather than shrinking or clipping an arbitrary neighbour.
        return packedLayout(preferredOrder, heightsPx, viewportPx, 0f)
    }

    if (!preferredOrder.all { persistedTopPx.containsKey(it) }) {
        return packedLayout(preferredOrder, heightsPx, viewportPx, gapPx)
    }

    val ordered =
        preferredOrder.sortedWith(
            compareBy<CapsuleLightBlock> { persistedTopPx[it] ?: 0f }
                .thenBy { preferredOrder.indexOf(it) },
        )

    val result = mutableMapOf<CapsuleLightBlock, Float>()
    var floor = 0f
    ordered.forEach { block ->
        val height = heightsPx.getValue(block)
        val desired =
            (persistedTopPx[block] ?: floor)
                .coerceIn(0f, (viewportPx - height).coerceAtLeast(0f))
        val y = desired.coerceAtLeast(floor)
        result[block] = y
        floor = y + height + gapPx
    }

    val last = ordered.last()
    val lastBottom = result.getValue(last) + heightsPx.getValue(last)
    if (lastBottom > viewportPx) {
        var ceiling = viewportPx
        for (index in ordered.lastIndex downTo 0) {
            val block = ordered[index]
            val height = heightsPx.getValue(block)
            val maxTop = (ceiling - height).coerceAtLeast(0f)
            result[block] = minOf(result.getValue(block), maxTop)
            ceiling = result.getValue(block) - gapPx
        }
    }

    // One last deterministic forward pass guarantees no overlap after the backward correction.
    floor = 0f
    ordered.forEach { block ->
        val y = result.getValue(block).coerceAtLeast(floor)
        result[block] = y
        floor = y + heightsPx.getValue(block) + gapPx
    }

    return CanvasLayout(ordered, result)
}

private fun solveAnchored(
    base: CanvasLayout,
    moving: CapsuleLightBlock,
    requestedTopPx: Float,
    heightsPx: Map<CapsuleLightBlock, Float>,
    viewportPx: Float,
    gapPx: Float,
): CanvasLayout {
    val movingHeight = heightsPx.getValue(moving)
    val fixed =
        base.order
            .filterNot { it == moving }
            .sortedBy { base.topPx.getValue(it) }

    val movingCenter = requestedTopPx + movingHeight / 2f
    val insertIndex =
        fixed.indexOfFirst { block ->
            movingCenter <
                base.topPx.getValue(block) +
                    heightsPx.getValue(block) / 2f
        }.let { if (it < 0) fixed.size else it }

    val solvedOrder =
        fixed.toMutableList().apply {
            add(insertIndex, moving)
        }

    val before = solvedOrder.take(insertIndex)
    val after = solvedOrder.drop(insertIndex + 1)

    val minimumAbove =
        before.sumOf { heightsPx.getValue(it).toDouble() }.toFloat() +
            gapPx * before.size
    val minimumBelow =
        after.sumOf { heightsPx.getValue(it).toDouble() }.toFloat() +
            gapPx * after.size

    val minMovingTop = minimumAbove
    val maxMovingTop =
        (viewportPx - movingHeight - minimumBelow)
            .coerceAtLeast(minMovingTop)
    val movingTop =
        requestedTopPx.coerceIn(minMovingTop, maxMovingTop)

    val result = base.topPx.toMutableMap()
    result[moving] = movingTop

    var nextTop = movingTop
    for (index in insertIndex - 1 downTo 0) {
        val block = solvedOrder[index]
        val height = heightsPx.getValue(block)
        val maxTop = nextTop - gapPx - height
        val current = base.topPx.getValue(block)
        val top = minOf(current, maxTop).coerceAtLeast(0f)
        result[block] = top
        nextTop = top
    }

    var previousBottom = movingTop + movingHeight
    for (index in insertIndex + 1..solvedOrder.lastIndex) {
        val block = solvedOrder[index]
        val height = heightsPx.getValue(block)
        val minTop = previousBottom + gapPx
        val current = base.topPx.getValue(block)
        val maxTop = (viewportPx - height).coerceAtLeast(minTop)
        val top = maxOf(current, minTop).coerceAtMost(maxTop)
        result[block] = top
        previousBottom = top + height
    }

    return CanvasLayout(solvedOrder, result)
}

private fun rectIsFree(
    topPx: Float,
    heightPx: Float,
    moving: CapsuleLightBlock,
    base: CanvasLayout,
    heightsPx: Map<CapsuleLightBlock, Float>,
    gapPx: Float,
): Boolean {
    val bottom = topPx + heightPx
    return base.order
        .asSequence()
        .filterNot { it == moving }
        .all { block ->
            val otherTop = base.topPx.getValue(block)
            val otherBottom = otherTop + heightsPx.getValue(block)
            bottom + gapPx <= otherTop || topPx >= otherBottom + gapPx
        }
}

private fun resolvePreview(
    moving: CapsuleLightBlock,
    fingerTopPx: Float,
    base: CanvasLayout,
    heightsPx: Map<CapsuleLightBlock, Float>,
    viewportPx: Float,
    gapPx: Float,
    gridStepPx: Float,
    realSnapPx: Float,
    preferredRealSnapPx: Float,
): Pair<CanvasPreview, List<Float>> {
    val movingHeight = heightsPx.getValue(moving)
    val maxTop = (viewportPx - movingHeight).coerceAtLeast(0f)
    val clampedFingerTop = fingerTopPx.coerceIn(0f, maxTop)

    data class RealCandidate(
        val top: Float,
        val distance: Float,
        val threshold: Float,
    )

    val realCandidates = mutableListOf<RealCandidate>()
    base.order
        .filterNot { it == moving }
        .forEach { neighbour ->
            val neighbourTop = base.topPx.getValue(neighbour)
            val neighbourHeight = heightsPx.getValue(neighbour)
            val threshold =
                if (preferredPair(neighbour, moving)) {
                    preferredRealSnapPx
                } else {
                    realSnapPx
                }

            val before = neighbourTop - gapPx - movingHeight
            if (before >= -0.5f) {
                val solved =
                    solveAnchored(
                        base = base,
                        moving = moving,
                        requestedTopPx = before,
                        heightsPx = heightsPx,
                        viewportPx = viewportPx,
                        gapPx = gapPx,
                    )
                val actual = solved.topPx.getValue(moving)
                realCandidates +=
                    RealCandidate(
                        top = actual,
                        distance = abs(clampedFingerTop - actual),
                        threshold = threshold,
                    )
            }

            val after = neighbourTop + neighbourHeight + gapPx
            if (after <= maxTop + 0.5f) {
                val solved =
                    solveAnchored(
                        base = base,
                        moving = moving,
                        requestedTopPx = after,
                        heightsPx = heightsPx,
                        viewportPx = viewportPx,
                        gapPx = gapPx,
                    )
                val actual = solved.topPx.getValue(moving)
                realCandidates +=
                    RealCandidate(
                        top = actual,
                        distance = abs(clampedFingerTop - actual),
                        threshold = threshold,
                    )
            }
        }

    val bestReal =
        realCandidates
            .distinctBy { it.top.roundToInt() }
            .minByOrNull { it.distance }

    val freeCells = mutableListOf<Float>()
    var cell = 0f
    while (cell <= maxTop + 0.5f) {
        if (
            rectIsFree(
                topPx = cell,
                heightPx = movingHeight,
                moving = moving,
                base = base,
                heightsPx = heightsPx,
                gapPx = gapPx,
            )
        ) {
            freeCells += cell
        }
        cell += gridStepPx
    }
    if (
        freeCells.none { abs(it - maxTop) <= 0.5f } &&
        rectIsFree(
            topPx = maxTop,
            heightPx = movingHeight,
            moving = moving,
            base = base,
            heightsPx = heightsPx,
            gapPx = gapPx,
        )
    ) {
        freeCells += maxTop
    }

    if (bestReal != null && bestReal.distance <= bestReal.threshold) {
        val layout =
            solveAnchored(
                base = base,
                moving = moving,
                requestedTopPx = bestReal.top,
                heightsPx = heightsPx,
                viewportPx = viewportPx,
                gapPx = gapPx,
            )
        return CanvasPreview(layout, CanvasTargetKind.REAL) to freeCells
    }

    val bestCell = freeCells.minByOrNull { abs(clampedFingerTop - it) }
    if (bestCell != null) {
        val layout =
            solveAnchored(
                base = base,
                moving = moving,
                requestedTopPx = bestCell,
                heightsPx = heightsPx,
                viewportPx = viewportPx,
                gapPx = gapPx,
            )
        return CanvasPreview(
            layout = layout,
            kind = CanvasTargetKind.EMPTY,
            selectedCellTopPx = layout.topPx.getValue(moving),
        ) to freeCells
    }

    val fallbackTop =
        bestReal?.top
            ?: base.topPx.getValue(moving)
    val fallback =
        solveAnchored(
            base = base,
            moving = moving,
            requestedTopPx = fallbackTop,
            heightsPx = heightsPx,
            viewportPx = viewportPx,
            gapPx = gapPx,
        )
    return CanvasPreview(fallback, CanvasTargetKind.FALLBACK) to freeCells
}

@Composable
internal fun CapsuleLightConstraintCanvas(
    order: List<CapsuleLightBlock>,
    positionsDp: Map<CapsuleLightBlock, Float>,
    editable: Boolean,
    viewportHeight: Dp,
    onLayoutSettled: (
        order: List<CapsuleLightBlock>,
        positionsDp: Map<CapsuleLightBlock, Float>,
    ) -> Unit,
    onPositionsNormalized: (Map<CapsuleLightBlock, Float>) -> Unit,
    onEditStarted: () -> Unit,
    externalGestureActive: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (CapsuleLightBlock) -> Unit,
) {
    val density = LocalDensity.current
    val viewportPx = with(density) { viewportHeight.toPx() }
    val gapPx = with(density) { CanvasDockGap.toPx() }
    val gridStepPx = with(density) { CanvasGridStep.toPx() }
    val realSnapPx = with(density) { CanvasRealSnap.toPx() }
    val preferredRealSnapPx = with(density) { CanvasPreferredRealSnap.toPx() }

    val latestOnLayoutSettled by rememberUpdatedState(onLayoutSettled)
    val latestOnPositionsNormalized by rememberUpdatedState(onPositionsNormalized)
    val latestOnEditStarted by rememberUpdatedState(onEditStarted)

    val measuredHeights = remember { mutableStateMapOf<CapsuleLightBlock, Float>() }
    var dragged by remember { mutableStateOf<CapsuleLightBlock?>(null) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }
    var preview by remember { mutableStateOf<CanvasPreview?>(null) }
    var freeCells by remember { mutableStateOf<List<Float>>(emptyList()) }

    val persistedPx =
        positionsDp.mapValues { (_, valueDp) ->
            with(density) { valueDp.dp.toPx() }
        }

    val allMeasured = order.all { measuredHeights.containsKey(it) }
    val baseLayout =
        if (allMeasured) {
            normalizeLayout(
                preferredOrder = order,
                persistedTopPx = persistedPx,
                heightsPx = measuredHeights,
                viewportPx = viewportPx,
                gapPx = gapPx,
            )
        } else {
            CanvasLayout(order, emptyMap())
        }

    LaunchedEffect(baseLayout.topPx, allMeasured, externalGestureActive, positionsDp) {
        if (!allMeasured || externalGestureActive || dragged != null) return@LaunchedEffect
        val normalizedDp =
            baseLayout.topPx.mapValues { (_, px) ->
                with(density) { px.toDp().value }
            }
        val materiallyDifferent =
            order.any { block ->
                abs(
                    (normalizedDp[block] ?: 0f) -
                        (positionsDp[block] ?: Float.NaN),
                ) > 0.15f
            } || positionsDp.size != order.size
        if (materiallyDifferent) {
            latestOnPositionsNormalized(normalizedDp)
        }
    }

    Box(
        modifier =
            modifier
                .height(viewportHeight)
                .clipToBounds(),
    ) {
        if (editable && allMeasured) {
            val guideColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
            val selectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.52f)
            val selectedFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
            val moving = dragged
            val guideCells =
                if (moving != null) {
                    freeCells
                } else {
                    buildList {
                        var y = 0f
                        while (y <= viewportPx + 0.5f) {
                            val occupied =
                                baseLayout.order.any { block ->
                                    val top = baseLayout.topPx.getValue(block)
                                    val bottom = top + measuredHeights.getValue(block)
                                    y in (top - gapPx)..(bottom + gapPx)
                                }
                            if (!occupied) add(y)
                            y += gridStepPx
                        }
                    }
                }
            val selectedTop =
                preview
                    ?.takeIf { it.kind == CanvasTargetKind.EMPTY }
                    ?.selectedCellTopPx
            val selectedHeight =
                moving?.let { measuredHeights[it] ?: 0f } ?: 0f

            Canvas(Modifier.matchParentSize()) {
                guideCells.forEach { y ->
                    drawLine(
                        color = guideColor,
                        start = Offset(size.width / 2f - 34.dp.toPx(), y),
                        end = Offset(size.width / 2f + 34.dp.toPx(), y),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                if (selectedTop != null && selectedHeight > 0f) {
                    val inset = 18.dp.toPx()
                    drawRoundRect(
                        color = selectedFill,
                        topLeft = Offset(inset, selectedTop),
                        size =
                            Size(
                                width = (size.width - inset * 2f).coerceAtLeast(0f),
                                height = selectedHeight,
                            ),
                        cornerRadius =
                            androidx.compose.ui.geometry.CornerRadius(
                                18.dp.toPx(),
                                18.dp.toPx(),
                            ),
                    )
                    drawRoundRect(
                        color = selectedColor,
                        topLeft = Offset(inset, selectedTop),
                        size =
                            Size(
                                width = (size.width - inset * 2f).coerceAtLeast(0f),
                                height = selectedHeight,
                            ),
                        cornerRadius =
                            androidx.compose.ui.geometry.CornerRadius(
                                18.dp.toPx(),
                                18.dp.toPx(),
                            ),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
        }

        order.forEach { block ->
            key(block) {
                val baseTop = baseLayout.topPx[block] ?: 0f
                val targetTop =
                    preview
                        ?.layout
                        ?.topPx
                        ?.get(block)
                        ?: baseTop
                val isDragged = dragged == block

                val neighbourTranslation by
                    animateFloatAsState(
                        targetValue =
                            if (!isDragged) {
                                targetTop - baseTop
                            } else {
                                0f
                            },
                        animationSpec =
                            spring(
                                dampingRatio = 0.88f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        label = "capsuleConstraintNeighbour",
                    )

                val rawFingerTop =
                    if (isDragged) {
                        val height = measuredHeights[block] ?: 0f
                        (baseTop + dragDeltaY)
                            .coerceIn(
                                0f,
                                (viewportPx - height).coerceAtLeast(0f),
                            )
                    } else {
                        baseTop
                    }

                val movingTranslation =
                    if (isDragged) {
                        val selected = preview
                        val selectedTop = selected?.layout?.topPx?.get(block) ?: rawFingerTop
                        val attraction =
                            when (selected?.kind) {
                                CanvasTargetKind.REAL -> 0.76f
                                CanvasTargetKind.EMPTY -> 0.34f
                                else -> 0f
                            }
                        (
                            rawFingerTop +
                                (selectedTop - rawFingerTop) * attraction -
                                baseTop
                            )
                    } else {
                        neighbourTranslation
                    }

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = baseTop.roundToInt(),
                                )
                            }
                            .onSizeChanged { size ->
                                val measured = size.height.toFloat()
                                if (
                                    abs((measuredHeights[block] ?: -1f) - measured) >
                                    0.5f
                                ) {
                                    measuredHeights[block] = measured
                                }
                            }
                            .graphicsLayer {
                                translationY = movingTranslation
                            }
                            .zIndex(if (isDragged) 4f else 1f),
                ) {
                    content(block)

                    if (editable && allMeasured) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .width(CanvasHandleWidth)
                                    .height(CanvasHandleHeight)
                                    .pointerInput(block, viewportPx) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                latestOnEditStarted()
                                                dragged = block
                                                dragDeltaY = 0f
                                                preview = null
                                                freeCells = emptyList()
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragDeltaY += amount.y

                                                val height =
                                                    measuredHeights[block]
                                                        ?: return@detectDragGesturesAfterLongPress
                                                val fingerTop =
                                                    (baseTop + dragDeltaY)
                                                        .coerceIn(
                                                            0f,
                                                            (
                                                                viewportPx -
                                                                    height
                                                                ).coerceAtLeast(0f),
                                                        )
                                                val (nextPreview, cells) =
                                                    resolvePreview(
                                                        moving = block,
                                                        fingerTopPx = fingerTop,
                                                        base = baseLayout,
                                                        heightsPx = measuredHeights,
                                                        viewportPx = viewportPx,
                                                        gapPx = gapPx,
                                                        gridStepPx = gridStepPx,
                                                        realSnapPx = realSnapPx,
                                                        preferredRealSnapPx =
                                                            preferredRealSnapPx,
                                                    )
                                                preview = nextPreview
                                                freeCells = cells
                                            },
                                            onDragEnd = {
                                                val settled =
                                                    preview
                                                        ?: CanvasPreview(
                                                            layout = baseLayout,
                                                            kind = CanvasTargetKind.FALLBACK,
                                                        )
                                                val settledDp =
                                                    settled.layout.topPx.mapValues { (_, px) ->
                                                        with(density) { px.toDp().value }
                                                    }
                                                latestOnLayoutSettled(
                                                    settled.layout.order,
                                                    settledDp,
                                                )
                                                dragged = null
                                                dragDeltaY = 0f
                                                preview = null
                                                freeCells = emptyList()
                                            },
                                            onDragCancel = {
                                                dragged = null
                                                dragDeltaY = 0f
                                                preview = null
                                                freeCells = emptyList()
                                            },
                                        )
                                    },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(
                                Modifier
                                    .width(28.dp)
                                    .height(4.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
                                        RoundedCornerShape(100.dp),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}
