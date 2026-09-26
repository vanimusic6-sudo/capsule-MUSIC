/*
 * Capsule MUSIC
 * Light clay editor v2.
 *
 * The outer player editor is intentionally position based. The old gap-based editor tried to
 * infer a position from order + whitespace, which made empty-space drops ambiguous and allowed
 * unrelated state changes to move/hide blocks. This canvas has exactly one source of truth:
 * a top position for every real Light container.
 */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

private val LightCanvasDockGap = 8.dp
private val LightCanvasCellStep = 24.dp
private val LightCanvasRealMagnet = 58.dp
private val LightCanvasPreferredMagnet = 92.dp
private val LightCanvasGuideWidth = 72.dp

internal val CapsuleLightCanvasPositionsBaseEncoded = ""

internal fun decodeCapsuleLightCanvasPositions(raw: String): Map<CapsuleLightBlock, Float> {
    if (raw.isBlank()) return emptyMap()

    val result = mutableMapOf<CapsuleLightBlock, Float>()
    raw.split(',').forEach { token ->
        val parts = token.split('=', limit = 2)
        if (parts.size != 2) return@forEach
        val block =
            runCatching { CapsuleLightBlock.valueOf(parts[0].trim()) }
                .getOrNull()
                ?: return@forEach
        val value = parts[1].trim().toFloatOrNull()
            ?.takeIf { it.isFinite() }
            ?: return@forEach
        result[block] = value.coerceAtLeast(0f)
    }

    return if (result.keys.all { it in CapsuleLightBaseOrder }) result else emptyMap()
}

internal fun encodeCapsuleLightCanvasPositions(
    positionsDp: Map<CapsuleLightBlock, Float>,
): String =
    CapsuleLightBaseOrder
        .mapNotNull { block ->
            positionsDp[block]
                ?.takeIf { it.isFinite() && it >= 0f }
                ?.let { value ->
                    "${block.name}=${"%.2f".format(Locale.US, value)}"
                }
        }
        .joinToString(",")

private enum class LightDropKind {
    DOCK,
    CELL,
}

private enum class LightDockSide {
    BEFORE,
    AFTER,
}

private data class LightBounds(
    val baseTopPx: Float,
    val heightPx: Float,
)

private data class LightDropTarget(
    val kind: LightDropKind,
    val topPx: Float,
    val positionsPx: Map<CapsuleLightBlock, Float>,
    val order: List<CapsuleLightBlock>,
    val anchor: CapsuleLightBlock? = null,
    val side: LightDockSide? = null,
)

private fun preferredPair(
    a: CapsuleLightBlock?,
    b: CapsuleLightBlock,
): Boolean {
    if (a == null || a == b) return false
    val pair = setOf(a, b)
    return pair == setOf(CapsuleLightBlock.ARTWORK, CapsuleLightBlock.LYRIC) ||
        pair == setOf(CapsuleLightBlock.ARTWORK, CapsuleLightBlock.METADATA) ||
        pair == setOf(CapsuleLightBlock.LYRIC, CapsuleLightBlock.METADATA) ||
        pair == setOf(CapsuleLightBlock.METADATA, CapsuleLightBlock.PROGRESS) ||
        pair == setOf(CapsuleLightBlock.PROGRESS, CapsuleLightBlock.MODE_SWITCH) ||
        pair == setOf(CapsuleLightBlock.MODE_SWITCH, CapsuleLightBlock.CONTROLS)
}

private fun compactPositions(
    order: List<CapsuleLightBlock>,
    heights: Map<CapsuleLightBlock, Float>,
    gapPx: Float,
): Map<CapsuleLightBlock, Float> {
    var y = 0f
    return buildMap {
        order.forEachIndexed { index, block ->
            if (index > 0) y += gapPx
            put(block, y)
            y += heights[block] ?: 0f
        }
    }
}

private fun normalizedStoredPositions(
    order: List<CapsuleLightBlock>,
    requested: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
    canvasHeightPx: Float,
    gapPx: Float,
): Pair<List<CapsuleLightBlock>, Map<CapsuleLightBlock, Float>> {
    if (heights.keys.containsAll(order).not()) return order to emptyMap()

    val totalMin =
        order.sumOf { (heights[it] ?: 0f).toDouble() }.toFloat() +
            gapPx * (order.size - 1).coerceAtLeast(0)

    if (totalMin > canvasHeightPx + 0.5f) {
        // The resizer is responsible for keeping this impossible state from being created.
        // Until its parent clamps, compacting is still safer than clipping a random block.
        return order to compactPositions(order, heights, gapPx)
    }

    val hasCompleteStored =
        requested.keys.containsAll(order) &&
            order.all { requested[it]?.isFinite() == true }

    if (!hasCompleteStored) {
        return order to compactPositions(order, heights, gapPx)
    }

    val sorted =
        order.sortedWith(
            compareBy<CapsuleLightBlock> { requested[it] ?: Float.MAX_VALUE }
                .thenBy { order.indexOf(it) },
        )

    val out = mutableMapOf<CapsuleLightBlock, Float>()
    var minimumTop = 0f
    sorted.forEachIndexed { index, block ->
        val h = heights[block] ?: 0f
        val requestedTop = requested[block] ?: minimumTop
        val maxTop = (canvasHeightPx - h).coerceAtLeast(0f)
        val top = requestedTop.coerceIn(minimumTop, maxTop)
        out[block] = top
        minimumTop = top + h + if (index < sorted.lastIndex) gapPx else 0f
    }

    val last = sorted.lastOrNull()
    val overflow =
        if (last == null) {
            0f
        } else {
            ((out[last] ?: 0f) + (heights[last] ?: 0f) - canvasHeightPx)
                .coerceAtLeast(0f)
        }

    if (overflow > 0f) {
        var nextTop = canvasHeightPx
        for (index in sorted.indices.reversed()) {
            val block = sorted[index]
            val h = heights[block] ?: 0f
            val maxTop =
                nextTop - h - if (index < sorted.lastIndex) gapPx else 0f
            out[block] = minOf(out[block] ?: maxTop, maxTop)
            nextTop = out[block] ?: 0f
        }
    }

    if ((out[sorted.firstOrNull()] ?: 0f) < -0.5f) {
        return order to compactPositions(order, heights, gapPx)
    }

    return sorted to out
}

private fun insertionOrder(
    dragged: CapsuleLightBlock,
    desiredTopPx: Float,
    baseOrder: List<CapsuleLightBlock>,
    basePositions: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
): List<CapsuleLightBlock> {
    val draggedCenter = desiredTopPx + (heights[dragged] ?: 0f) / 2f
    val others =
        baseOrder
            .filterNot { it == dragged }
            .sortedBy { basePositions[it] ?: 0f }

    val index =
        others.indexOfFirst { other ->
            val center =
                (basePositions[other] ?: 0f) +
                    (heights[other] ?: 0f) / 2f
            draggedCenter < center
        }.let { if (it < 0) others.size else it }

    return others.toMutableList().apply { add(index, dragged) }
}

/**
 * Keeps the dragged block exactly at [draggedTopPx] and moves only blocks that actually collide
 * with it or with another displaced block. If preserving every rectangle inside the canvas is
 * impossible, the target is rejected rather than clipping/shrinking an unrelated block.
 */
private fun solveAt(
    dragged: CapsuleLightBlock,
    draggedTopPx: Float,
    order: List<CapsuleLightBlock>,
    basePositions: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
    canvasHeightPx: Float,
    gapPx: Float,
): Map<CapsuleLightBlock, Float>? {
    val dragIndex = order.indexOf(dragged)
    if (dragIndex < 0) return null

    val draggedHeight = heights[dragged] ?: return null
    if (draggedTopPx < -0.5f || draggedTopPx + draggedHeight > canvasHeightPx + 0.5f) {
        return null
    }

    val out = basePositions.toMutableMap()
    out[dragged] = draggedTopPx

    // Push the chain above upward only as much as needed.
    for (index in dragIndex - 1 downTo 0) {
        val block = order[index]
        val next = order[index + 1]
        val h = heights[block] ?: return null
        val maxTop = (out[next] ?: return null) - gapPx - h
        out[block] = minOf(basePositions[block] ?: maxTop, maxTop)
    }

    // Push the chain below downward only as much as needed.
    for (index in dragIndex + 1..order.lastIndex) {
        val block = order[index]
        val previous = order[index - 1]
        val previousBottom =
            (out[previous] ?: return null) +
                (heights[previous] ?: return null)
        val minTop = previousBottom + gapPx
        out[block] = maxOf(basePositions[block] ?: minTop, minTop)
    }

    val first = order.firstOrNull() ?: return out
    val last = order.lastOrNull() ?: return out
    val firstTop = out[first] ?: return null
    val lastBottom =
        (out[last] ?: return null) +
            (heights[last] ?: return null)

    if (firstTop < -0.5f || lastBottom > canvasHeightPx + 0.5f) return null

    return out
}

private fun overlapsExisting(
    dragged: CapsuleLightBlock,
    topPx: Float,
    heightPx: Float,
    basePositions: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
    gapPx: Float,
): Boolean =
    basePositions.any { (block, otherTop) ->
        if (block == dragged) return@any false
        val otherHeight = heights[block] ?: 0f
        val aTop = topPx - gapPx
        val aBottom = topPx + heightPx + gapPx
        val bTop = otherTop
        val bBottom = otherTop + otherHeight
        aTop < bBottom && aBottom > bTop
    }

private fun resolveTarget(
    dragged: CapsuleLightBlock,
    desiredTopPx: Float,
    baseOrder: List<CapsuleLightBlock>,
    basePositions: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
    canvasHeightPx: Float,
    gapPx: Float,
    cellStepPx: Float,
    normalMagnetPx: Float,
    preferredMagnetPx: Float,
): LightDropTarget? {
    val draggedHeight = heights[dragged] ?: return null
    val clampedDesired =
        desiredTopPx.coerceIn(
            0f,
            (canvasHeightPx - draggedHeight).coerceAtLeast(0f),
        )
    val order =
        insertionOrder(
            dragged = dragged,
            desiredTopPx = clampedDesired,
            baseOrder = baseOrder,
            basePositions = basePositions,
            heights = heights,
        )
    val index = order.indexOf(dragged)
    if (index < 0) return null

    data class DockCandidate(
        val topPx: Float,
        val distancePx: Float,
        val anchor: CapsuleLightBlock,
        val side: LightDockSide,
        val preferred: Boolean,
        val solved: Map<CapsuleLightBlock, Float>,
    )

    val docks = mutableListOf<DockCandidate>()

    val previous = order.getOrNull(index - 1)
    if (previous != null) {
        val top =
            (basePositions[previous] ?: 0f) +
                (heights[previous] ?: 0f) +
                gapPx
        val solved =
            solveAt(
                dragged = dragged,
                draggedTopPx = top,
                order = order,
                basePositions = basePositions,
                heights = heights,
                canvasHeightPx = canvasHeightPx,
                gapPx = gapPx,
            )
        if (solved != null) {
            docks +=
                DockCandidate(
                    topPx = top,
                    distancePx = abs(clampedDesired - top),
                    anchor = previous,
                    side = LightDockSide.AFTER,
                    preferred = preferredPair(previous, dragged),
                    solved = solved,
                )
        }
    }

    val next = order.getOrNull(index + 1)
    if (next != null) {
        val top =
            (basePositions[next] ?: 0f) -
                gapPx -
                draggedHeight
        val solved =
            solveAt(
                dragged = dragged,
                draggedTopPx = top,
                order = order,
                basePositions = basePositions,
                heights = heights,
                canvasHeightPx = canvasHeightPx,
                gapPx = gapPx,
            )
        if (solved != null) {
            docks +=
                DockCandidate(
                    topPx = top,
                    distancePx = abs(clampedDesired - top),
                    anchor = next,
                    side = LightDockSide.BEFORE,
                    preferred = preferredPair(next, dragged),
                    solved = solved,
                )
        }
    }

    val winningDock =
        docks
            .filter { candidate ->
                candidate.distancePx <=
                    if (candidate.preferred) preferredMagnetPx else normalMagnetPx
            }
            .minWithOrNull(
                compareByDescending<DockCandidate> { it.preferred }
                    .thenBy { it.distancePx }
                    .thenBy { it.side.ordinal },
            )

    if (winningDock != null) {
        return LightDropTarget(
            kind = LightDropKind.DOCK,
            topPx = winningDock.topPx,
            positionsPx = winningDock.solved,
            order = order,
            anchor = winningDock.anchor,
            side = winningDock.side,
        )
    }

    // Empty cells only exist where the dragged rectangle is actually empty in the frozen scene.
    // The highlighted cell therefore cannot promise a landing that later displaces/overwrites a
    // real component.
    val maxTop = (canvasHeightPx - draggedHeight).coerceAtLeast(0f)
    val cellCandidates = mutableListOf<LightDropTarget>()
    var cellTop = 0f
    while (cellTop <= maxTop + 0.5f) {
        if (
            !overlapsExisting(
                dragged = dragged,
                topPx = cellTop,
                heightPx = draggedHeight,
                basePositions = basePositions,
                heights = heights,
                gapPx = gapPx,
            )
        ) {
            val cellOrder =
                insertionOrder(
                    dragged = dragged,
                    desiredTopPx = cellTop,
                    baseOrder = baseOrder,
                    basePositions = basePositions,
                    heights = heights,
                )
            val solved =
                solveAt(
                    dragged = dragged,
                    draggedTopPx = cellTop,
                    order = cellOrder,
                    basePositions = basePositions,
                    heights = heights,
                    canvasHeightPx = canvasHeightPx,
                    gapPx = gapPx,
                )
            if (solved != null) {
                cellCandidates +=
                    LightDropTarget(
                        kind = LightDropKind.CELL,
                        topPx = cellTop,
                        positionsPx = solved,
                        order = cellOrder,
                    )
            }
        }
        cellTop += cellStepPx
    }

    val winningCell =
        cellCandidates.minByOrNull { abs(clampedDesired - it.topPx) }
    if (winningCell != null) return winningCell

    // No free cell exists. A feasible real neighbour is safer than clipping or shrinking a block,
    // even if the pointer is farther away than the normal magnetic radius.
    val fallbackDock =
        docks.minWithOrNull(
            compareByDescending<DockCandidate> { it.preferred }
                .thenBy { it.distancePx }
                .thenBy { it.side.ordinal },
        )

    return fallbackDock?.let {
        LightDropTarget(
            kind = LightDropKind.DOCK,
            topPx = it.topPx,
            positionsPx = it.solved,
            order = order,
            anchor = it.anchor,
            side = it.side,
        )
    }
}

@Composable
internal fun CapsuleLightCanvasV2(
    order: List<CapsuleLightBlock>,
    positionsDp: Map<CapsuleLightBlock, Float>,
    editable: Boolean,
    viewportHeight: Dp,
    onLayoutSettled: (
        positionsDp: Map<CapsuleLightBlock, Float>,
        order: List<CapsuleLightBlock>,
    ) -> Unit,
    onEditStarted: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (CapsuleLightBlock) -> Unit,
) {
    val density = LocalDensity.current
    val canvasHeightPx = with(density) { viewportHeight.toPx() }
    val gapPx = with(density) { LightCanvasDockGap.toPx() }
    val cellStepPx = with(density) { LightCanvasCellStep.toPx() }
    val normalMagnetPx = with(density) { LightCanvasRealMagnet.toPx() }
    val preferredMagnetPx = with(density) { LightCanvasPreferredMagnet.toPx() }

    val bounds = remember { mutableStateMapOf<CapsuleLightBlock, LightBounds>() }
    var dragged by remember { mutableStateOf<CapsuleLightBlock?>(null) }
    var dragOriginTopPx by remember { mutableFloatStateOf(0f) }
    var dragDeltaPx by remember { mutableFloatStateOf(0f) }
    var frozenOrder by remember { mutableStateOf(order) }
    var frozenPositionsPx by
        remember {
            mutableStateOf<Map<CapsuleLightBlock, Float>>(emptyMap())
        }
    var target by remember { mutableStateOf<LightDropTarget?>(null) }

    val latestPositionsDp by rememberUpdatedState(positionsDp)
    val latestOrder by rememberUpdatedState(order)
    val latestOnLayoutSettled by rememberUpdatedState(onLayoutSettled)
    val latestOnEditStarted by rememberUpdatedState(onEditStarted)

    val heightsPx =
        remember(bounds.toMap()) {
            bounds.mapValues { it.value.heightPx }
        }
    val allMeasured =
        order.isNotEmpty() &&
            order.all { (heightsPx[it] ?: 0f) > 0f }

    val requestedPositionsPx =
        positionsDp.mapValues { (_, value) ->
            with(density) { value.dp.toPx() }
        }

    val (resolvedOrder, resolvedPositionsPx) =
        if (allMeasured) {
            normalizedStoredPositions(
                order = order,
                requested = requestedPositionsPx,
                heights = heightsPx,
                canvasHeightPx = canvasHeightPx,
                gapPx = gapPx,
            )
        } else {
            order to emptyMap()
        }

    val activePositionsPx =
        if (dragged != null && target != null) {
            target!!.positionsPx
        } else {
            resolvedPositionsPx
        }

    // A persisted layout from another screen height or an older editor is normalized once. This
    // never writes during drag.
    LaunchedEffect(
        allMeasured,
        resolvedOrder,
        resolvedPositionsPx,
        dragged,
    ) {
        if (!allMeasured || dragged != null || resolvedPositionsPx.isEmpty()) return@LaunchedEffect

        val normalizedDp =
            resolvedPositionsPx.mapValues { (_, value) ->
                with(density) { value.toDp().value }
            }
        val needsWrite =
            latestPositionsDp.keys.containsAll(order).not() ||
                order.any { block ->
                    abs(
                        (latestPositionsDp[block] ?: -1f) -
                            (normalizedDp[block] ?: -1f),
                    ) > 0.25f
                } ||
                resolvedOrder != latestOrder

        if (needsWrite && latestPositionsDp.isNotEmpty()) {
            latestOnLayoutSettled(normalizedDp, resolvedOrder)
        }
    }

    Box(
        modifier =
            modifier
                .height(viewportHeight)
                .clipToBounds(),
    ) {
        if (editable && allMeasured) {
            val occupiedPositions =
                if (dragged != null) frozenPositionsPx else resolvedPositionsPx
            val occupiedDragged = dragged

            val guideColor =
                MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)

            Canvas(Modifier.fillMaxSize()) {
                val halfWidth = LightCanvasGuideWidth.toPx() / 2f
                val stroke = 3.dp.toPx()

                var y = 0f
                while (y <= size.height + 0.5f) {
                    val occupied =
                        occupiedPositions.any { (block, top) ->
                            if (block == occupiedDragged) return@any false
                            val h = heightsPx[block] ?: 0f
                            y >= top - gapPx && y <= top + h + gapPx
                        }
                    if (!occupied) {
                        drawLine(
                            color = guideColor,
                            start = Offset(size.width / 2f - halfWidth, y),
                            end = Offset(size.width / 2f + halfWidth, y),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                    y += cellStepPx
                }
            }

            val selected = target
            if (dragged != null && selected?.kind == LightDropKind.CELL) {
                val selectedHeight =
                    with(density) {
                        (heightsPx[dragged] ?: 0f).toDp()
                    }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    x = 0,
                                    y = selected.topPx.roundToInt(),
                                )
                            }
                            .height(selectedHeight)
                            .zIndex(1f)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.055f),
                                RoundedCornerShape(18.dp),
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.62f),
                                RoundedCornerShape(18.dp),
                            ),
                )
            }
        }

        // Every outer block is measured independently. Nothing receives "remaining height" from
        // a Column, so growing artwork can never compress metadata/progress/mode/controls.
        order.forEach { block ->
            key(block) {
                val selected = dragged == block
                val normalTop =
                    activePositionsPx[block] ?: 0f

                val desiredDraggedTop =
                    if (selected) {
                        val h = heightsPx[block] ?: 0f
                        (dragOriginTopPx + dragDeltaPx)
                            .coerceIn(
                                0f,
                                (canvasHeightPx - h).coerceAtLeast(0f),
                            )
                    } else {
                        normalTop
                    }

                val visualTop =
                    if (selected) {
                        val selectedTarget = target
                        if (selectedTarget?.kind == LightDropKind.DOCK) {
                            // The held object moves toward the fixed neighbour. The neighbour only
                            // moves if the collision solver genuinely needs to make room.
                            desiredDraggedTop * 0.28f +
                                selectedTarget.topPx * 0.72f
                        } else {
                            desiredDraggedTop
                        }
                    } else {
                        normalTop
                    }

                val animatedTop by
                    animateFloatAsState(
                        targetValue = visualTop,
                        animationSpec =
                            if (selected) {
                                spring(
                                    dampingRatio = 1f,
                                    stiffness = Spring.StiffnessHigh,
                                )
                            } else {
                                spring(
                                    dampingRatio = 0.86f,
                                    stiffness = Spring.StiffnessMediumLow,
                                )
                            },
                        label = "CapsuleLightCanvasV2_${block.name}",
                    )

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(unbounded = true)
                            .onSizeChanged { size ->
                                val nextHeight = size.height.toFloat()
                                val previous = bounds[block]?.heightPx
                                if (previous == null || abs(previous - nextHeight) > 0.5f) {
                                    bounds[block] =
                                        LightBounds(
                                            baseTopPx = 0f,
                                            heightPx = nextHeight,
                                        )
                                }
                            }
                            .graphicsLayer {
                                translationY = animatedTop
                            }
                            .zIndex(if (selected) 4f else 0f),
                ) {
                    content(block)

                    if (editable) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .width(56.dp)
                                    .height(22.dp)
                                    .pointerInput(block, allMeasured) {
                                        if (!allMeasured) return@pointerInput

                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                val currentPositions =
                                                    resolvedPositionsPx
                                                if (currentPositions.isEmpty()) {
                                                    return@detectDragGesturesAfterLongPress
                                                }

                                                latestOnEditStarted()
                                                dragged = block
                                                frozenOrder = resolvedOrder
                                                frozenPositionsPx = currentPositions
                                                dragOriginTopPx =
                                                    currentPositions[block] ?: 0f
                                                dragDeltaPx = 0f

                                                target =
                                                    resolveTarget(
                                                        dragged = block,
                                                        desiredTopPx = dragOriginTopPx,
                                                        baseOrder = resolvedOrder,
                                                        basePositions = currentPositions,
                                                        heights = heightsPx,
                                                        canvasHeightPx = canvasHeightPx,
                                                        gapPx = gapPx,
                                                        cellStepPx = cellStepPx,
                                                        normalMagnetPx = normalMagnetPx,
                                                        preferredMagnetPx = preferredMagnetPx,
                                                    )
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragDeltaPx += amount.y

                                                val h = heightsPx[block] ?: 0f
                                                val desired =
                                                    (dragOriginTopPx + dragDeltaPx)
                                                        .coerceIn(
                                                            0f,
                                                            (
                                                                canvasHeightPx -
                                                                    h
                                                                ).coerceAtLeast(0f),
                                                        )

                                                target =
                                                    resolveTarget(
                                                        dragged = block,
                                                        desiredTopPx = desired,
                                                        baseOrder = frozenOrder,
                                                        basePositions = frozenPositionsPx,
                                                        heights = heightsPx,
                                                        canvasHeightPx = canvasHeightPx,
                                                        gapPx = gapPx,
                                                        cellStepPx = cellStepPx,
                                                        normalMagnetPx = normalMagnetPx,
                                                        preferredMagnetPx = preferredMagnetPx,
                                                    )
                                            },
                                            onDragEnd = {
                                                val settled = target
                                                if (settled != null) {
                                                    latestOnLayoutSettled(
                                                        settled.positionsPx.mapValues {
                                                            (_, value) ->
                                                            with(density) {
                                                                value.toDp().value
                                                            }
                                                        },
                                                        settled.order,
                                                    )
                                                }
                                                dragged = null
                                                dragDeltaPx = 0f
                                                frozenPositionsPx = emptyMap()
                                                target = null
                                            },
                                            onDragCancel = {
                                                dragged = null
                                                dragDeltaPx = 0f
                                                frozenPositionsPx = emptyMap()
                                                target = null
                                            },
                                        )
                                    },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(
                                Modifier
                                    .width(30.dp)
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
