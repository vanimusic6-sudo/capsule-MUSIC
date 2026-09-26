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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.Locale
import kotlin.math.abs

private val LightCanvasDockGap = 8.dp
private val LightCanvasRealMagnet = 58.dp
private val LightCanvasPreferredMagnet = 92.dp

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

internal fun lightCanvasMeasurementsReady(
    order: List<CapsuleLightBlock>,
    measuredHeightsPx: Map<CapsuleLightBlock, Float>,
): Boolean =
    order.isNotEmpty() &&
        order.all { measuredHeightsPx.containsKey(it) }

private enum class LightDropKind {
    DOCK,
    EDGE,
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

private fun projectOrderedPositions(
    order: List<CapsuleLightBlock>,
    preferredPositions: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
    startPx: Float,
    endPx: Float,
    gapPx: Float,
): Map<CapsuleLightBlock, Float>? {
    if (order.isEmpty()) return emptyMap()

    var minimumHeight = gapPx * (order.size - 1).coerceAtLeast(0)
    order.forEach { block ->
        minimumHeight += heights[block] ?: return null
    }

    val availableHeight = endPx - startPx
    if (minimumHeight > availableHeight + 0.5f) return null

    val maxSlack = (availableHeight - minimumHeight).coerceAtLeast(0f)
    var cumulativeMin = 0f
    var previousSlack = 0f
    val result = linkedMapOf<CapsuleLightBlock, Float>()

    order.forEachIndexed { index, block ->
        val height = heights[block] ?: return null
        val preferredTop = preferredPositions[block] ?: (startPx + cumulativeMin)
        val preferredSlack = preferredTop - startPx - cumulativeMin
        val slack =
            preferredSlack
                .coerceIn(0f, maxSlack)
                .coerceAtLeast(previousSlack)

        result[block] = startPx + cumulativeMin + slack
        previousSlack = slack
        cumulativeMin += height
        if (index < order.lastIndex) cumulativeMin += gapPx
    }

    return result
}

internal fun normalizedStoredPositions(
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
        // A transient oversize measurement can happen while artwork resize is being committed.
        // Never feed an inverted range into coerceIn and never delete a block: render the compact
        // scene until the parent clamps the artwork back into the legal budget.
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

    val projected =
        projectOrderedPositions(
            order = sorted,
            preferredPositions = requested,
            heights = heights,
            startPx = 0f,
            endPx = canvasHeightPx,
            gapPx = gapPx,
        ) ?: compactPositions(sorted, heights, gapPx)

    return sorted to projected
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
    val legalRange =
        slotTopRange(
            dragged = dragged,
            slotIndex = dragIndex,
            order = order,
            heights = heights,
            canvasHeightPx = canvasHeightPx,
            gapPx = gapPx,
        ) ?: return null

    if (
        draggedTopPx < legalRange.first - 0.5f ||
        draggedTopPx > legalRange.second + 0.5f
    ) {
        return null
    }

    val exactDraggedTop =
        draggedTopPx.coerceIn(
            legalRange.first,
            legalRange.second,
        )

    val above = order.subList(0, dragIndex)
    val below = order.subList(dragIndex + 1, order.size)

    val upperEnd =
        if (above.isEmpty()) {
            0f
        } else {
            exactDraggedTop - gapPx
        }
    val lowerStart =
        if (below.isEmpty()) {
            canvasHeightPx
        } else {
            exactDraggedTop + draggedHeight + gapPx
        }

    val upper =
        if (above.isEmpty()) {
            emptyMap()
        } else {
            projectOrderedPositions(
                order = above,
                preferredPositions = basePositions,
                heights = heights,
                startPx = 0f,
                endPx = upperEnd,
                gapPx = gapPx,
            ) ?: return null
        }

    val lower =
        if (below.isEmpty()) {
            emptyMap()
        } else {
            projectOrderedPositions(
                order = below,
                preferredPositions = basePositions,
                heights = heights,
                startPx = lowerStart,
                endPx = canvasHeightPx,
                gapPx = gapPx,
            ) ?: return null
        }

    return buildMap {
        putAll(upper)
        put(dragged, exactDraggedTop)
        putAll(lower)
    }
}

private data class LightLiveLayout(
    val topPx: Float,
    val positionsPx: Map<CapsuleLightBlock, Float>,
    val order: List<CapsuleLightBlock>,
)

private fun resolveLiveLayout(
    dragged: CapsuleLightBlock,
    desiredTopPx: Float,
    baseOrder: List<CapsuleLightBlock>,
    basePositions: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
    canvasHeightPx: Float,
    gapPx: Float,
): LightLiveLayout? {
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

    val range =
        slotTopRange(
            dragged = dragged,
            slotIndex = index,
            order = order,
            heights = heights,
            canvasHeightPx = canvasHeightPx,
            gapPx = gapPx,
        ) ?: return null

    val liveTop =
        clampedDesired.coerceIn(
            range.first,
            range.second,
        )
    val solved =
        solveAt(
            dragged = dragged,
            draggedTopPx = liveTop,
            order = order,
            basePositions = basePositions,
            heights = heights,
            canvasHeightPx = canvasHeightPx,
            gapPx = gapPx,
        ) ?: return null

    return LightLiveLayout(
        topPx = liveTop,
        positionsPx = solved,
        order = order,
    )
}

private data class LightDockCandidate(
    val topPx: Float,
    val distancePx: Float,
    val anchor: CapsuleLightBlock,
    val side: LightDockSide,
    val preferred: Boolean,
    val solved: Map<CapsuleLightBlock, Float>,
    val order: List<CapsuleLightBlock>,
)

internal enum class LightEdgeReplacement {
    TOP,
    BOTTOM,
}

internal fun chooseLightEdgeReplacement(
    currentIndex: Int,
    lastIndex: Int,
    commandCenterPx: Float,
    firstCenterPx: Float,
    lastCenterPx: Float,
): LightEdgeReplacement? =
    when {
        currentIndex > 0 && commandCenterPx <= firstCenterPx + 0.5f ->
            LightEdgeReplacement.TOP
        currentIndex < lastIndex && commandCenterPx >= lastCenterPx - 0.5f ->
            LightEdgeReplacement.BOTTOM
        else -> null
    }

/**
 * Frozen real blocks, always sorted by their actual on-canvas position.
 *
 * A drag never invents a "gap index" from pointer coordinates. Every CELL below is an insertion
 * slot between two real rectangles (plus the top/bottom edge slots), so preview and drop share the
 * exact same finite set of legal destinations.
 */
private fun fixedBlocksForDrag(
    dragged: CapsuleLightBlock,
    baseOrder: List<CapsuleLightBlock>,
    basePositions: Map<CapsuleLightBlock, Float>,
): List<CapsuleLightBlock> =
    baseOrder
        .filterNot { it == dragged }
        .sortedWith(
            compareBy<CapsuleLightBlock> { basePositions[it] ?: Float.MAX_VALUE }
                .thenBy { baseOrder.indexOf(it) },
        )

/**
 * Returns the legal top range for [dragged] when it occupies [slotIndex] in [order].
 *
 * The range comes from the amount of real rectangle space that must remain above and below the
 * dragged block. It does not shrink any neighbour and it does not allow a rectangle to leave the
 * canvas.
 */
private fun slotTopRange(
    dragged: CapsuleLightBlock,
    slotIndex: Int,
    order: List<CapsuleLightBlock>,
    heights: Map<CapsuleLightBlock, Float>,
    canvasHeightPx: Float,
    gapPx: Float,
): Pair<Float, Float>? {
    val draggedHeight = heights[dragged] ?: return null
    if (slotIndex !in order.indices || order[slotIndex] != dragged) return null

    var requiredAbove = 0f
    for (index in 0 until slotIndex) {
        requiredAbove += heights[order[index]] ?: return null
        requiredAbove += gapPx
    }

    var requiredBelow = 0f
    for (index in slotIndex + 1..order.lastIndex) {
        requiredBelow += gapPx
        requiredBelow += heights[order[index]] ?: return null
    }

    val minTop = requiredAbove
    val maxTop = canvasHeightPx - draggedHeight - requiredBelow
    return if (minTop <= maxTop + 0.5f) {
        minTop to maxTop.coerceAtLeast(minTop)
    } else {
        null
    }
}

/**
 * Builds the actual empty-position cells for the held block.
 *
 * There is exactly one CELL per insertion slot. The cell is placed in the visual middle of the
 * frozen gap when that gap is already open; when the gap is too small, the cell is clamped into the
 * legal range and [solveAt] moves only the neighbours that must make room. This is what makes the
 * highlighted preview identical to the eventual release result.
 */
private fun buildCellTargets(
    dragged: CapsuleLightBlock,
    baseOrder: List<CapsuleLightBlock>,
    basePositions: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
    canvasHeightPx: Float,
    gapPx: Float,
): List<LightDropTarget> {
    val draggedHeight = heights[dragged] ?: return emptyList()
    val fixed =
        fixedBlocksForDrag(
            dragged = dragged,
            baseOrder = baseOrder,
            basePositions = basePositions,
        )

    return buildList {
        for (slotIndex in 0..fixed.size) {
            val order =
                fixed.toMutableList().apply {
                    add(slotIndex, dragged)
                }
            val range =
                slotTopRange(
                    dragged = dragged,
                    slotIndex = slotIndex,
                    order = order,
                    heights = heights,
                    canvasHeightPx = canvasHeightPx,
                    gapPx = gapPx,
                ) ?: continue

            val previous = fixed.getOrNull(slotIndex - 1)
            val next = fixed.getOrNull(slotIndex)

            val afterPrevious =
                previous?.let { block ->
                    (basePositions[block] ?: 0f) +
                        (heights[block] ?: 0f) +
                        gapPx
                } ?: 0f
            val beforeNext =
                next?.let { block ->
                    (basePositions[block] ?: canvasHeightPx) -
                        gapPx -
                        draggedHeight
                } ?: (canvasHeightPx - draggedHeight)

            val preferredTop =
                when {
                    previous == null -> 0f
                    next == null -> (canvasHeightPx - draggedHeight).coerceAtLeast(0f)
                    else -> (afterPrevious + beforeNext) / 2f
                }

            val cellTop =
                preferredTop.coerceIn(
                    range.first,
                    range.second,
                )
            val solved =
                solveAt(
                    dragged = dragged,
                    draggedTopPx = cellTop,
                    order = order,
                    basePositions = basePositions,
                    heights = heights,
                    canvasHeightPx = canvasHeightPx,
                    gapPx = gapPx,
                ) ?: continue

            add(
                LightDropTarget(
                    kind = LightDropKind.CELL,
                    topPx = cellTop,
                    positionsPx = solved,
                    order = order,
                ),
            )
        }
    }
}

/**
 * Real-neighbour magnets are generated from every stationary block, not only whichever neighbour
 * happens to be inferred from the pointer's current insertion order.
 *
 * The anchor itself stays at its frozen position. If room has to be created, [solveAt] moves the
 * other chain instead. This makes the held object snap to the stationary object, never vice versa.
 */
private fun buildDockCandidates(
    dragged: CapsuleLightBlock,
    desiredTopPx: Float,
    baseOrder: List<CapsuleLightBlock>,
    basePositions: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
    canvasHeightPx: Float,
    gapPx: Float,
): List<LightDockCandidate> {
    val draggedHeight = heights[dragged] ?: return emptyList()
    val fixed =
        fixedBlocksForDrag(
            dragged = dragged,
            baseOrder = baseOrder,
            basePositions = basePositions,
        )

    return buildList {
        fixed.forEachIndexed { anchorIndex, anchor ->
            val anchorTop = basePositions[anchor] ?: return@forEachIndexed
            val anchorHeight = heights[anchor] ?: return@forEachIndexed

            fun addCandidate(
                side: LightDockSide,
                insertionIndex: Int,
                topPx: Float,
            ) {
                val order =
                    fixed.toMutableList().apply {
                        add(insertionIndex, dragged)
                    }
                val solved =
                    solveAt(
                        dragged = dragged,
                        draggedTopPx = topPx,
                        order = order,
                        basePositions = basePositions,
                        heights = heights,
                        canvasHeightPx = canvasHeightPx,
                        gapPx = gapPx,
                    ) ?: return

                // Docking is allowed only if the advertised real neighbour truly remains fixed.
                if (abs((solved[anchor] ?: anchorTop) - anchorTop) > 0.75f) return

                add(
                    LightDockCandidate(
                        topPx = topPx,
                        distancePx = abs(desiredTopPx - topPx),
                        anchor = anchor,
                        side = side,
                        preferred = preferredPair(anchor, dragged),
                        solved = solved,
                        order = order,
                    ),
                )
            }

            addCandidate(
                side = LightDockSide.BEFORE,
                insertionIndex = anchorIndex,
                topPx = anchorTop - gapPx - draggedHeight,
            )
            addCandidate(
                side = LightDockSide.AFTER,
                insertionIndex = anchorIndex + 1,
                topPx = anchorTop + anchorHeight + gapPx,
            )
        }
    }
}

private fun resolveEdgeReplacement(
    dragged: CapsuleLightBlock,
    commandTopPx: Float,
    baseOrder: List<CapsuleLightBlock>,
    basePositions: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
    canvasHeightPx: Float,
    gapPx: Float,
): LightDropTarget? {
    val draggedHeight = heights[dragged] ?: return null
    val fixed =
        fixedBlocksForDrag(
            dragged = dragged,
            baseOrder = baseOrder,
            basePositions = basePositions,
        )
    if (fixed.isEmpty()) return null

    val current =
        baseOrder.sortedWith(
            compareBy<CapsuleLightBlock> { basePositions[it] ?: Float.MAX_VALUE }
                .thenBy { baseOrder.indexOf(it) },
        )
    val currentIndex = current.indexOf(dragged)
    if (currentIndex < 0) return null

    val commandCenter = commandTopPx + draggedHeight / 2f
    val first = fixed.first()
    val last = fixed.last()
    val firstTop = basePositions[first] ?: return null
    val firstCenter = firstTop + (heights[first] ?: return null) / 2f
    val lastTop = basePositions[last] ?: return null
    val lastHeight = heights[last] ?: return null
    val lastCenter = lastTop + lastHeight / 2f

    val edge =
        chooseLightEdgeReplacement(
            currentIndex = currentIndex,
            lastIndex = current.lastIndex,
            commandCenterPx = commandCenter,
            firstCenterPx = firstCenter,
            lastCenterPx = lastCenter,
        )

    if (edge == LightEdgeReplacement.TOP) {
        val edgeOrder = buildList {
            add(dragged)
            addAll(fixed)
        }
        val range =
            slotTopRange(
                dragged = dragged,
                slotIndex = 0,
                order = edgeOrder,
                heights = heights,
                canvasHeightPx = canvasHeightPx,
                gapPx = gapPx,
            ) ?: return null
        val edgeTop = firstTop.coerceIn(range.first, range.second)
        val solved =
            solveAt(
                dragged = dragged,
                draggedTopPx = edgeTop,
                order = edgeOrder,
                basePositions = basePositions,
                heights = heights,
                canvasHeightPx = canvasHeightPx,
                gapPx = gapPx,
            ) ?: return null

        return LightDropTarget(
            kind = LightDropKind.EDGE,
            topPx = edgeTop,
            positionsPx = solved,
            order = edgeOrder,
            anchor = first,
            side = LightDockSide.BEFORE,
        )
    }

    if (edge == LightEdgeReplacement.BOTTOM) {
        val edgeOrder = buildList {
            addAll(fixed)
            add(dragged)
        }
        val range =
            slotTopRange(
                dragged = dragged,
                slotIndex = edgeOrder.lastIndex,
                order = edgeOrder,
                heights = heights,
                canvasHeightPx = canvasHeightPx,
                gapPx = gapPx,
            ) ?: return null
        val preferredTop = lastTop + lastHeight - draggedHeight
        val edgeTop = preferredTop.coerceIn(range.first, range.second)
        val solved =
            solveAt(
                dragged = dragged,
                draggedTopPx = edgeTop,
                order = edgeOrder,
                basePositions = basePositions,
                heights = heights,
                canvasHeightPx = canvasHeightPx,
                gapPx = gapPx,
            ) ?: return null

        return LightDropTarget(
            kind = LightDropKind.EDGE,
            topPx = edgeTop,
            positionsPx = solved,
            order = edgeOrder,
            anchor = last,
            side = LightDockSide.AFTER,
        )
    }

    return null
}

private fun resolveTarget(
    dragged: CapsuleLightBlock,
    desiredTopPx: Float,
    baseOrder: List<CapsuleLightBlock>,
    basePositions: Map<CapsuleLightBlock, Float>,
    heights: Map<CapsuleLightBlock, Float>,
    canvasHeightPx: Float,
    gapPx: Float,
    normalMagnetPx: Float,
    preferredMagnetPx: Float,
): LightDropTarget? {
    val draggedHeight = heights[dragged] ?: return null
    val clampedDesired =
        desiredTopPx.coerceIn(
            0f,
            (canvasHeightPx - draggedHeight).coerceAtLeast(0f),
        )

    resolveEdgeReplacement(
        dragged = dragged,
        commandTopPx = clampedDesired,
        baseOrder = baseOrder,
        basePositions = basePositions,
        heights = heights,
        canvasHeightPx = canvasHeightPx,
        gapPx = gapPx,
    )?.let { return it }

    val docks =
        buildDockCandidates(
            dragged = dragged,
            desiredTopPx = clampedDesired,
            baseOrder = baseOrder,
            basePositions = basePositions,
            heights = heights,
            canvasHeightPx = canvasHeightPx,
            gapPx = gapPx,
        )

    // A real neighbour always wins while the held rectangle is inside that neighbour's magnetic
    // radius. Preferred semantic pairs merely get a larger radius; they do not override a much
    // closer physical neighbour.
    val winningDock =
        docks
            .filter { candidate ->
                candidate.distancePx <=
                    if (candidate.preferred) preferredMagnetPx else normalMagnetPx
            }
            .minWithOrNull(
                compareBy<LightDockCandidate> { candidate ->
                    val radius =
                        if (candidate.preferred) preferredMagnetPx else normalMagnetPx
                    candidate.distancePx / radius.coerceAtLeast(1f)
                }
                    .thenBy { it.distancePx }
                    .thenByDescending { it.preferred }
                    .thenBy { it.side.ordinal },
            )

    if (winningDock != null) {
        return LightDropTarget(
            kind = LightDropKind.DOCK,
            topPx = winningDock.topPx,
            positionsPx = winningDock.solved,
            order = winningDock.order,
            anchor = winningDock.anchor,
            side = winningDock.side,
        )
    }

    val cells =
        buildCellTargets(
            dragged = dragged,
            baseOrder = baseOrder,
            basePositions = basePositions,
            heights = heights,
            canvasHeightPx = canvasHeightPx,
            gapPx = gapPx,
        )
    val winningCell =
        cells.minByOrNull { abs(clampedDesired - it.topPx) }
    if (winningCell != null) return winningCell

    // An impossible geometry never clips, shrinks or deletes another element. If a valid CELL does
    // not exist, keep the nearest feasible real dock; if even that does not exist, reject the move.
    val fallbackDock =
        docks.minWithOrNull(
            compareBy<LightDockCandidate> { candidate ->
                val radius =
                    if (candidate.preferred) preferredMagnetPx else normalMagnetPx
                candidate.distancePx / radius.coerceAtLeast(1f)
            }
                .thenBy { it.distancePx }
                .thenByDescending { it.preferred }
                .thenBy { it.side.ordinal },
        )

    return fallbackDock?.let {
        LightDropTarget(
            kind = LightDropKind.DOCK,
            topPx = it.topPx,
            positionsPx = it.solved,
            order = it.order,
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
    externalGestureActive: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable (CapsuleLightBlock, Dp?) -> Unit,
) {
    val density = LocalDensity.current
    val canvasHeightPx = with(density) { viewportHeight.toPx() }
    val gapPx = with(density) { LightCanvasDockGap.toPx() }
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
    var liveLayout by remember { mutableStateOf<LightLiveLayout?>(null) }

    val latestPositionsDp by rememberUpdatedState(positionsDp)
    val latestOrder by rememberUpdatedState(order)
    val latestOnLayoutSettled by rememberUpdatedState(onLayoutSettled)
    val latestOnEditStarted by rememberUpdatedState(onEditStarted)

    val heightsPx =
        remember(bounds.toMap()) {
            bounds.mapValues { it.value.heightPx }
        }
    // "Measured" means Compose has reported a size, not that the size is positive.
    // Optional blocks (most notably LYRIC when the feature is disabled outside edit mode)
    // legitimately measure to 0 px. Treating 0 as "not measured" used to disable the entire
    // position engine and collapse every block to Y=0 after leaving the editor.
    val allMeasured =
        lightCanvasMeasurementsReady(
            order = order,
            measuredHeightsPx = heightsPx,
        )

    val nonArtworkMeasured =
        lightCanvasMeasurementsReady(
            order = order.filterNot { it == CapsuleLightBlock.ARTWORK },
            measuredHeightsPx = heightsPx,
        )
    val maxArtworkHeightDp =
        if (nonArtworkMeasured) {
            val otherHeightPx =
                order
                    .filterNot { it == CapsuleLightBlock.ARTWORK }
                    .sumOf { (heightsPx[it] ?: 0f).toDouble() }
                    .toFloat()
            val requiredGapsPx =
                gapPx * (order.size - 1).coerceAtLeast(0)
            val availablePx =
                (canvasHeightPx - otherHeightPx - requiredGapsPx)
                    .coerceAtLeast(0f)
            with(density) { availablePx.toDp() }
        } else {
            null
        }

    val requestedPositionsPx =
        positionsDp.mapValues { (_, value) ->
            with(density) { value.dp.toPx() }
        }

    val (resolvedOrder, resolvedPositionsPx) =
        if (allMeasured) {
            if (
                externalGestureActive &&
                requestedPositionsPx.keys.containsAll(order)
            ) {
                order to (
                    projectOrderedPositions(
                        order = order,
                        preferredPositions = requestedPositionsPx,
                        heights = heightsPx,
                        startPx = 0f,
                        endPx = canvasHeightPx,
                        gapPx = gapPx,
                    ) ?: compactPositions(order, heightsPx, gapPx)
                )
            } else {
                normalizedStoredPositions(
                    order = order,
                    requested = requestedPositionsPx,
                    heights = heightsPx,
                    canvasHeightPx = canvasHeightPx,
                    gapPx = gapPx,
                )
            }
        } else {
            order to emptyMap()
        }

    val activePositionsPx =
        when {
            // A magnetic DOCK moves the command block away from the raw finger coordinate.
            // Dependants must therefore follow the DOCK's solved geometry too; otherwise the held
            // block can look stationary while neighbours react to invisible finger travel.
            dragged != null &&
                (target?.kind == LightDropKind.DOCK || target?.kind == LightDropKind.EDGE) ->
                target!!.positionsPx
            dragged != null && liveLayout != null ->
                liveLayout!!.positionsPx
            resolvedPositionsPx.isNotEmpty() -> resolvedPositionsPx
            // A saved custom scene must never flash/collapse to the origin while Compose is still
            // reporting child sizes. These provisional tops are replaced by the normalized scene
            // as soon as every child (including legitimate 0-height children) has been measured.
            requestedPositionsPx.isNotEmpty() -> requestedPositionsPx
            else -> emptyMap()
        }

    // A persisted layout from another screen height or an older editor is normalized once. This
    // never writes during drag.
    LaunchedEffect(
        allMeasured,
        resolvedOrder,
        resolvedPositionsPx,
        dragged,
        externalGestureActive,
    ) {
        if (
            !allMeasured ||
            dragged != null ||
            externalGestureActive ||
            resolvedPositionsPx.isEmpty()
        ) {
            return@LaunchedEffect
        }

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

        if (needsWrite && (latestPositionsDp.isNotEmpty() || editable)) {
            latestOnLayoutSettled(normalizedDp, resolvedOrder)
        }
    }

    Box(
        modifier =
            modifier
                .height(viewportHeight)
                .clipToBounds(),
    ) {

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
                        val liveTop =
                            liveLayout?.topPx
                                ?: desiredDraggedTop
                        val selectedTarget = target
                        if (
                            selectedTarget?.kind == LightDropKind.DOCK ||
                            selectedTarget?.kind == LightDropKind.EDGE
                        ) {
                            // DOCK snaps to a stationary neighbour; EDGE replaces the outermost
                            // neighbour and pushes that chain inward. Both are actual command
                            // positions, so dependants and the held block must share this top.
                            selectedTarget.topPx
                        } else {
                            liveTop
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
                                    dampingRatio = 0.92f,
                                    stiffness = Spring.StiffnessMedium,
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
                    content(
                        block,
                        if (block == CapsuleLightBlock.ARTWORK) {
                            maxArtworkHeightDp
                        } else {
                            null
                        },
                    )

                    if (editable) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .width(56.dp)
                                    .height(22.dp)
                                    .pointerInput(
                                        block,
                                        allMeasured,
                                        resolvedOrder,
                                        resolvedPositionsPx,
                                        heightsPx,
                                        canvasHeightPx,
                                    ) {
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

                                                liveLayout =
                                                    resolveLiveLayout(
                                                        dragged = block,
                                                        desiredTopPx = dragOriginTopPx,
                                                        baseOrder = resolvedOrder,
                                                        basePositions = currentPositions,
                                                        heights = heightsPx,
                                                        canvasHeightPx = canvasHeightPx,
                                                        gapPx = gapPx,
                                                    )

                                                val commandTop =
                                                    liveLayout?.topPx
                                                        ?: dragOriginTopPx
                                                target =
                                                    resolveTarget(
                                                        dragged = block,
                                                        desiredTopPx = commandTop,
                                                        baseOrder = resolvedOrder,
                                                        basePositions = currentPositions,
                                                        heights = heightsPx,
                                                        canvasHeightPx = canvasHeightPx,
                                                        gapPx = gapPx,
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

                                                liveLayout =
                                                    resolveLiveLayout(
                                                        dragged = block,
                                                        desiredTopPx = desired,
                                                        baseOrder = frozenOrder,
                                                        basePositions = frozenPositionsPx,
                                                        heights = heightsPx,
                                                        canvasHeightPx = canvasHeightPx,
                                                        gapPx = gapPx,
                                                    )

                                                val commandTop =
                                                    liveLayout?.topPx
                                                        ?: desired
                                                target =
                                                    resolveTarget(
                                                        dragged = block,
                                                        desiredTopPx = commandTop,
                                                        baseOrder = frozenOrder,
                                                        basePositions = frozenPositionsPx,
                                                        heights = heightsPx,
                                                        canvasHeightPx = canvasHeightPx,
                                                        gapPx = gapPx,
                                                        normalMagnetPx = normalMagnetPx,
                                                        preferredMagnetPx = preferredMagnetPx,
                                                    )
                                            },
                                            onDragEnd = {
                                                val settledTarget = target
                                                val settledLive = liveLayout
                                                val settledPositions =
                                                    settledTarget?.positionsPx
                                                        ?: settledLive?.positionsPx
                                                val settledOrder =
                                                    settledTarget?.order
                                                        ?: settledLive?.order

                                                if (
                                                    settledPositions != null &&
                                                    settledOrder != null
                                                ) {
                                                    latestOnLayoutSettled(
                                                        settledPositions.mapValues {
                                                            (_, value) ->
                                                            with(density) {
                                                                value.toDp().value
                                                            }
                                                        },
                                                        settledOrder,
                                                    )
                                                }
                                                dragged = null
                                                dragDeltaPx = 0f
                                                frozenPositionsPx = emptyMap()
                                                target = null
                                                liveLayout = null
                                            },
                                            onDragCancel = {
                                                dragged = null
                                                dragDeltaPx = 0f
                                                frozenPositionsPx = emptyMap()
                                                target = null
                                                liveLayout = null
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
