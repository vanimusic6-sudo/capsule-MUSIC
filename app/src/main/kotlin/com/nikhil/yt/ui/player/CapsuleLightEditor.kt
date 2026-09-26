package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Capsule Light editor v3: hierarchy, not a flat grid.
 *
 * The outer level reorders the original Light containers. Inner rows only reorder the controls
 * that already belong to that container. This keeps the real Light visual language intact.
 */
internal enum class CapsuleLightBlock {
    ARTWORK,
    LYRIC,
    METADATA,
    PROGRESS,
    MODE_SWITCH,
    CONTROLS,
}

internal enum class CapsuleLightMetadataItem {
    TEXT,
    FAVORITE,
}

internal enum class CapsuleLightModeItem {
    SHUFFLE,
    AUDIO_VIDEO,
    SLEEP,
}

internal enum class CapsuleLightAvItem {
    AUDIO,
    VIDEO,
}

internal enum class CapsuleLightTransportItem {
    REPEAT,
    PREVIOUS,
    PLAY_PAUSE,
    NEXT,
    MENU,
}

internal val CapsuleLightBaseOrder: List<CapsuleLightBlock> = CapsuleLightBlock.entries.toList()
internal val CapsuleLightMetadataBaseOrder: List<CapsuleLightMetadataItem> = CapsuleLightMetadataItem.entries.toList()
internal val CapsuleLightModeBaseOrder: List<CapsuleLightModeItem> = CapsuleLightModeItem.entries.toList()
internal val CapsuleLightAvBaseOrder: List<CapsuleLightAvItem> = CapsuleLightAvItem.entries.toList()
internal val CapsuleLightTransportBaseOrder: List<CapsuleLightTransportItem> = CapsuleLightTransportItem.entries.toList()

internal val CapsuleLightBaseOrderEncoded = encodeEnumOrder(CapsuleLightBaseOrder)
internal val CapsuleLightMetadataBaseOrderEncoded = encodeEnumOrder(CapsuleLightMetadataBaseOrder)
internal val CapsuleLightModeBaseOrderEncoded = encodeEnumOrder(CapsuleLightModeBaseOrder)
internal val CapsuleLightAvBaseOrderEncoded = encodeEnumOrder(CapsuleLightAvBaseOrder)
internal val CapsuleLightTransportBaseOrderEncoded = encodeEnumOrder(CapsuleLightTransportBaseOrder)

internal val CapsuleLightBaseGaps: Map<CapsuleLightBlock, Float> =
    CapsuleLightBaseOrder.associateWith { 0f }

internal val CapsuleLightBaseGapsEncoded: String =
    CapsuleLightBaseOrder.joinToString(",") { "${it.name}=0" }

internal fun decodeCapsuleLightBlockGaps(raw: String): Map<CapsuleLightBlock, Float> {
    if (raw.isBlank()) return CapsuleLightBaseGaps

    val parsed = CapsuleLightBaseGaps.toMutableMap()
    raw.split(',').forEach { token ->
        val parts = token.split('=', limit = 2)
        if (parts.size != 2) return@forEach
        val block = runCatching { CapsuleLightBlock.valueOf(parts[0].trim()) }.getOrNull()
            ?: return@forEach
        val value = parts[1].trim().toFloatOrNull() ?: return@forEach
        parsed[block] = value.coerceIn(0f, 1000f)
    }
    return parsed
}

internal fun encodeCapsuleLightBlockGaps(gaps: Map<CapsuleLightBlock, Float>): String =
    CapsuleLightBaseOrder.joinToString(",") { block ->
        val value = (gaps[block] ?: 0f).coerceIn(0f, 1000f)
        "${block.name}=${"%.2f".format(java.util.Locale.US, value)}"
    }

private fun <T : Enum<T>> encodeEnumOrder(order: List<T>): String =
    order.joinToString(",") { it.name }

private inline fun <reified T : Enum<T>> decodeEnumOrder(
    raw: String,
    base: List<T>,
): List<T> {
    if (raw.isBlank()) return base
    val parsed =
        raw.split(',')
            .mapNotNull { token ->
                runCatching { enumValueOf<T>(token.trim()) }.getOrNull()
            }
    return if (
        parsed.size == base.size &&
        parsed.distinct().size == base.size &&
        parsed.toSet() == base.toSet()
    ) {
        parsed
    } else {
        base
    }
}

internal fun decodeCapsuleLightOrder(raw: String): List<CapsuleLightBlock> {
    if (raw.isBlank()) return CapsuleLightBaseOrder

    val parsed =
        raw.split(',')
            .mapNotNull { token ->
                runCatching { CapsuleLightBlock.valueOf(token.trim()) }.getOrNull()
            }

    if (
        parsed.size == CapsuleLightBaseOrder.size &&
        parsed.distinct().size == CapsuleLightBaseOrder.size &&
        parsed.toSet() == CapsuleLightBaseOrder.toSet()
    ) {
        return parsed
    }

    // v1 had five outer containers and kept the lyric line inside ARTWORK.
    // Preserve the user's old container order and insert the new LYRIC container next to artwork.
    val legacyBase = CapsuleLightBaseOrder.filterNot { it == CapsuleLightBlock.LYRIC }
    if (
        parsed.size == legacyBase.size &&
        parsed.distinct().size == legacyBase.size &&
        parsed.toSet() == legacyBase.toSet()
    ) {
        val migrated = parsed.toMutableList()
        val artworkIndex = migrated.indexOf(CapsuleLightBlock.ARTWORK)
        migrated.add((artworkIndex + 1).coerceAtMost(migrated.size), CapsuleLightBlock.LYRIC)
        return migrated
    }

    return CapsuleLightBaseOrder
}

internal fun encodeCapsuleLightOrder(order: List<CapsuleLightBlock>) =
    encodeEnumOrder(decodeCapsuleLightOrder(encodeEnumOrder(order)))

internal fun decodeCapsuleLightMetadataOrder(raw: String) =
    decodeEnumOrder(raw, CapsuleLightMetadataBaseOrder)
internal fun encodeCapsuleLightMetadataOrder(order: List<CapsuleLightMetadataItem>) =
    encodeEnumOrder(decodeCapsuleLightMetadataOrder(encodeEnumOrder(order)))

internal fun decodeCapsuleLightModeOrder(raw: String) =
    decodeEnumOrder(raw, CapsuleLightModeBaseOrder)
internal fun encodeCapsuleLightModeOrder(order: List<CapsuleLightModeItem>) =
    encodeEnumOrder(decodeCapsuleLightModeOrder(encodeEnumOrder(order)))

internal fun decodeCapsuleLightAvOrder(raw: String) =
    decodeEnumOrder(raw, CapsuleLightAvBaseOrder)
internal fun encodeCapsuleLightAvOrder(order: List<CapsuleLightAvItem>) =
    encodeEnumOrder(decodeCapsuleLightAvOrder(encodeEnumOrder(order)))

internal fun decodeCapsuleLightTransportOrder(raw: String) =
    decodeEnumOrder(raw, CapsuleLightTransportBaseOrder)
internal fun encodeCapsuleLightTransportOrder(order: List<CapsuleLightTransportItem>) =
    encodeEnumOrder(decodeCapsuleLightTransportOrder(encodeEnumOrder(order)))

internal object CapsuleLightEditorProcessGuard {
    @Volatile
    private var recoveryCheckedInThisProcess = false

    @Synchronized
    fun shouldCheckRecovery(): Boolean {
        if (recoveryCheckedInThisProcess) return false
        recoveryCheckedInThisProcess = true
        return true
    }

    @Synchronized
    internal fun resetForTesting() {
        recoveryCheckedInThisProcess = false
    }
}

private data class AxisBounds(
    val start: Float,
    val size: Float,
) {
    val center: Float get() = start + size / 2f
}

/**
 * Outer Light containers.
 *
 * While a drag is active the real composition order never changes. Neighbours only receive a
 * placement offset toward their virtual target slots. The real order is committed on ACTION_UP.
 * This avoids the one-frame "old slot/new slot" flash that happens when relayout wins the race
 * against a post-layout compensation animation.
 *
 * [gapsDp] is the free vertical space owned by each block. Dragging a block without crossing
 * another block changes that space, so the user can move a container lower instead of being
 * limited to swaps.
 */
@Composable
internal fun CapsuleLightReorderColumn(
    order: List<CapsuleLightBlock>,
    editable: Boolean,
    gapsDp: Map<CapsuleLightBlock, Float> = emptyMap(),
    viewportHeight: Dp,
    onOrderChange: (List<CapsuleLightBlock>) -> Unit,
    onOrderSettled: (List<CapsuleLightBlock>) -> Unit,
    onGapSettled: (CapsuleLightBlock, Float) -> Unit = { _, _ -> },
    onGapsNormalized: (Map<CapsuleLightBlock, Float>) -> Unit = {},
    onEditStarted: () -> Unit = {},
    externalGestureActive: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable (CapsuleLightBlock) -> Unit,
) {
    if (!editable) {
        Column(
            modifier =
                modifier
                    .height(viewportHeight)
                    .clipToBounds(),
        ) {
            order.forEach { block ->
                val gap = (gapsDp[block] ?: 0f).coerceAtLeast(0f)
                if (gap > 0f) Spacer(Modifier.height(gap.dp))
                key(block) { content(block) }
            }
        }
        return
    }

    val latestOrder by rememberUpdatedState(order)
    val latestOnOrderChange by rememberUpdatedState(onOrderChange)
    val latestOnOrderSettled by rememberUpdatedState(onOrderSettled)
    val latestOnGapSettled by rememberUpdatedState(onGapSettled)
    val latestOnGapsNormalized by rememberUpdatedState(onGapsNormalized)
    val latestOnEditStarted by rememberUpdatedState(onEditStarted)
    val density = LocalDensity.current

    val bounds = remember { mutableStateMapOf<CapsuleLightBlock, AxisBounds>() }
    var dragged by remember { mutableStateOf<CapsuleLightBlock?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var workingOrder by remember { mutableStateOf(order) }

    LaunchedEffect(order, dragged) {
        if (dragged == null) workingOrder = order
    }

    fun slotStart(
        item: CapsuleLightBlock,
        virtualOrder: List<CapsuleLightBlock>,
        draggedItem: CapsuleLightBlock?,
        gapDeltaPx: Float,
    ): Float? {
        val first =
            latestOrder
                .firstNotNullOfOrNull { bounds[it]?.start }
                ?: return null
        var cursor = first
        virtualOrder.forEach { candidate ->
            if (candidate == item) return cursor
            val measured = bounds[candidate]?.size ?: return null
            cursor += measured
            if (candidate == draggedItem) cursor += gapDeltaPx
        }
        return null
    }

    val viewportHeightPx = with(density) { viewportHeight.toPx() }
    val allBlocksMeasured = latestOrder.all { bounds[it] != null }
    val effectiveGapsDp =
        if (!allBlocksMeasured) {
            gapsDp
        } else {
            var remainingGapPx =
                (
                    viewportHeightPx -
                        latestOrder.sumOf { block ->
                            val rawGapPx =
                                with(density) {
                                    ((effectiveGapsDp[block] ?: 0f).coerceAtLeast(0f)).dp.toPx()
                                }
                            ((bounds[block]?.size ?: 0f) - rawGapPx)
                                .coerceAtLeast(0f)
                                .toDouble()
                        }.toFloat()
                    )
                    .coerceAtLeast(0f)

            buildMap {
                latestOrder.forEach { block ->
                    val requestedPx =
                        with(density) {
                            ((effectiveGapsDp[block] ?: 0f).coerceAtLeast(0f)).dp.toPx()
                        }
                    val acceptedPx = requestedPx.coerceAtMost(remainingGapPx)
                    put(block, acceptedPx / density.density)
                    remainingGapPx = (remainingGapPx - acceptedPx).coerceAtLeast(0f)
                }
            }
        }

    LaunchedEffect(effectiveGapsDp, gapsDp, allBlocksMeasured) {
        if (
            allBlocksMeasured &&
            latestOrder.any { block ->
                kotlin.math.abs(
                    (effectiveGapsDp[block] ?: 0f) - (effectiveGapsDp[block] ?: 0f),
                ) > 0.05f
            }
        ) {
            latestOnGapsNormalized(effectiveGapsDp)
        }
    }

    val draggedBounds = dragged?.let(bounds::get)
    val draggedTargetStart =
        dragged?.let { item ->
            slotStart(
                item = item,
                virtualOrder = workingOrder,
                draggedItem = null,
                gapDeltaPx = 0f,
            )
        }
    val currentGapPx =
        dragged?.let { item ->
            with(density) {
                ((effectiveGapsDp[item] ?: 0f).coerceAtLeast(0f)).dp.toPx()
            }
        } ?: 0f
    val measuredTotalHeightPx =
        latestOrder.sumOf { block ->
            (bounds[block]?.size ?: 0f).toDouble()
        }.toFloat()
    val maxGapPx =
        if (measuredTotalHeightPx > 0f) {
            val heightWithoutCurrentGap =
                (measuredTotalHeightPx - currentGapPx).coerceAtLeast(0f)
            (viewportHeightPx - heightWithoutCurrentGap)
                .coerceAtLeast(currentGapPx.coerceAtLeast(0f))
        } else {
            viewportHeightPx.coerceAtLeast(0f)
        }
    val gapDeltaPx =
        if (draggedBounds != null && draggedTargetStart != null) {
            val residual = draggedBounds.start + dragOffsetY - draggedTargetStart
            (currentGapPx + residual).coerceIn(0f, maxGapPx) - currentGapPx
        } else {
            0f
        }

    Column(
        modifier =
            modifier
                .height(viewportHeight)
                .clipToBounds(),
    ) {
        // Important: render the committed order, not workingOrder.
        order.forEach { block ->
            key(block) {
                val gap = (effectiveGapsDp[block] ?: 0f).coerceAtLeast(0f)
                val selected = dragged == block
                val actual = bounds[block]
                val virtualStart =
                    slotStart(
                        item = block,
                        virtualOrder = workingOrder,
                        draggedItem = dragged,
                        gapDeltaPx = gapDeltaPx,
                    )
                val neighbourTarget =
                    if (!selected && actual != null && virtualStart != null) {
                        virtualStart - actual.start
                    } else {
                        0f
                    }
                val animatedNeighbourOffset by
                    animateFloatAsState(
                        targetValue = neighbourTarget,
                        animationSpec =
                            spring(
                                dampingRatio = 0.88f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        label = "capsuleOuterPush",
                    )

                if (gap > 0f) Spacer(Modifier.height(gap.dp))

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                bounds[block] =
                                    AxisBounds(
                                        start = coordinates.positionInParent().y - with(density) { gap.dp.toPx() },
                                        size = coordinates.size.height.toFloat() + with(density) { gap.dp.toPx() },
                                    )
                            }
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y =
                                        (
                                            when {
                                                selected -> dragOffsetY
                                                dragged == null && order == workingOrder -> 0f
                                                else -> animatedNeighbourOffset
                                            }
                                        ).roundToInt(),
                                )
                            }
                            .zIndex(if (selected) 4f else 0f),
                ) {
                    content(block)

                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .width(52.dp)
                                .height(20.dp)
                                .pointerInput(block) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            latestOnEditStarted()
                                            workingOrder = latestOrder
                                            dragged = block
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()

                                            val actualBounds = bounds[block]
                                                ?: return@detectDragGesturesAfterLongPress
                                            val gapPx =
                                                with(density) {
                                                    ((effectiveGapsDp[block] ?: 0f).coerceAtLeast(0f)).dp.toPx()
                                                }
                                            val contentStart = actualBounds.start + gapPx
                                            val contentHeight =
                                                (actualBounds.size - gapPx).coerceAtLeast(1f)
                                            val topInViewport = contentStart
                                            val minOffset = -topInViewport
                                            val maxOffset =
                                                (viewportHeightPx - contentHeight - topInViewport)
                                                    .coerceAtLeast(minOffset)

                                            dragOffsetY =
                                                (dragOffsetY + amount.y)
                                                    .coerceIn(minOffset, maxOffset)

                                            val currentIndex = workingOrder.indexOf(block)
                                            if (currentIndex < 0) return@detectDragGesturesAfterLongPress

                                            val visualCenter = actualBounds.center + dragOffsetY
                                            var targetIndex = currentIndex

                                            if (currentIndex > 0) {
                                                val previous = workingOrder[currentIndex - 1]
                                                val previousStart =
                                                    slotStart(
                                                        previous,
                                                        workingOrder,
                                                        block,
                                                        gapDeltaPx,
                                                    )
                                                val previousSize = bounds[previous]?.size
                                                if (
                                                    previousStart != null &&
                                                    previousSize != null &&
                                                    visualCenter < previousStart + previousSize / 2f
                                                ) {
                                                    targetIndex = currentIndex - 1
                                                }
                                            }

                                            if (
                                                targetIndex == currentIndex &&
                                                currentIndex < workingOrder.lastIndex
                                            ) {
                                                val next = workingOrder[currentIndex + 1]
                                                val nextStart =
                                                    slotStart(
                                                        next,
                                                        workingOrder,
                                                        block,
                                                        gapDeltaPx,
                                                    )
                                                val nextSize = bounds[next]?.size
                                                if (
                                                    nextStart != null &&
                                                    nextSize != null &&
                                                    visualCenter > nextStart + nextSize / 2f
                                                ) {
                                                    targetIndex = currentIndex + 1
                                                }
                                            }

                                            if (targetIndex != currentIndex) {
                                                val moved = workingOrder.toMutableList()
                                                moved.removeAt(currentIndex)
                                                moved.add(targetIndex, block)
                                                workingOrder = moved
                                            }
                                        },
                                        onDragEnd = {
                                            val settled = workingOrder
                                            val rawGapPx =
                                                (currentGapPx + gapDeltaPx)
                                                    .coerceIn(0f, maxGapPx)

                                            val actualBounds = bounds[block]
                                            val currentGapForBlockPx =
                                                with(density) {
                                                    ((effectiveGapsDp[block] ?: 0f)
                                                        .coerceAtLeast(0f)).dp.toPx()
                                                }
                                            val contentHeightPx =
                                                (
                                                    (actualBounds?.size ?: 0f) -
                                                        currentGapForBlockPx
                                                    ).coerceAtLeast(0f)
                                            val contentTopPx =
                                                (actualBounds?.start ?: 0f) +
                                                    currentGapForBlockPx +
                                                    dragOffsetY
                                            val distanceToBottomPx =
                                                viewportHeightPx -
                                                    (contentTopPx + contentHeightPx)
                                            val bottomMagnetPx = with(density) { 28.dp.toPx() }
                                            val gridPx = with(density) { 8.dp.toPx() }

                                            val anchoredGapPx =
                                                if (
                                                    settled.lastOrNull() == block &&
                                                    kotlin.math.abs(distanceToBottomPx) <= bottomMagnetPx
                                                ) {
                                                    (rawGapPx + distanceToBottomPx)
                                                        .coerceIn(0f, maxGapPx)
                                                } else {
                                                    (
                                                        kotlin.math.round(rawGapPx / gridPx) *
                                                            gridPx
                                                        ).coerceIn(0f, maxGapPx)
                                                }

                                            val finalGapDp =
                                                (anchoredGapPx / density.density)
                                                    .coerceAtLeast(0f)

                                            // Commit all state in the same input frame. Since every
                                            // neighbour is already visually at its future slot,
                                            // the physical relayout is invisible.
                                            latestOnGapSettled(block, finalGapDp)
                                            latestOnOrderChange(settled)
                                            latestOnOrderSettled(settled)
                                            dragged = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            dragged = null
                                            dragOffsetY = 0f
                                            workingOrder = latestOrder
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

/**
 * Same no-flash strategy for controls inside a native Light container. During a drag the Row's
 * real child order stays untouched; only placement offsets animate. Commit happens on release.
 */
@Composable
internal fun <T : Enum<T>> CapsuleLightReorderRow(
    order: List<T>,
    editable: Boolean,
    weightFor: (T) -> Float,
    onOrderChange: (List<T>) -> Unit,
    onOrderSettled: (List<T>) -> Unit,
    onEditStarted: () -> Unit,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    dragHandleOnly: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    if (!editable) {
        Row(
            modifier = modifier,
            verticalAlignment = verticalAlignment,
        ) {
            order.forEach { item ->
                key(item) {
                    Box(
                        modifier = Modifier.weight(weightFor(item)),
                        contentAlignment = Alignment.Center,
                    ) {
                        content(item)
                    }
                }
            }
        }
        return
    }

    val latestOrder by rememberUpdatedState(order)
    val latestOnOrderChange by rememberUpdatedState(onOrderChange)
    val latestOnOrderSettled by rememberUpdatedState(onOrderSettled)
    val latestOnEditStarted by rememberUpdatedState(onEditStarted)

    val bounds = remember { mutableStateMapOf<T, AxisBounds>() }
    var dragged by remember { mutableStateOf<T?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    var workingOrder by remember { mutableStateOf(order) }

    LaunchedEffect(order, dragged) {
        if (dragged == null) workingOrder = order
    }

    fun slotStart(item: T, virtualOrder: List<T>): Float? {
        val first =
            latestOrder
                .firstNotNullOfOrNull { bounds[it]?.start }
                ?: return null
        var cursor = first
        virtualOrder.forEach { candidate ->
            if (candidate == item) return cursor
            cursor += bounds[candidate]?.size ?: return null
        }
        return null
    }

    Row(
        modifier =
            modifier
                .clipToBounds()
                .onGloballyPositioned { coordinates ->
                    rowWidthPx = coordinates.size.width.toFloat()
                },
        verticalAlignment = verticalAlignment,
    ) {
        // Render committed order; workingOrder is only the virtual destination map.
        order.forEach { item ->
            key(item) {
                val selected = dragged == item
                val actual = bounds[item]
                val virtualStart = slotStart(item, workingOrder)
                val target =
                    if (!selected && actual != null && virtualStart != null) {
                        virtualStart - actual.start
                    } else {
                        0f
                    }
                val animatedOffset by
                    animateFloatAsState(
                        targetValue = target,
                        animationSpec =
                            spring(
                                dampingRatio = 0.88f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        label = "capsuleInnerPush",
                    )

                val dragGesture =
                    Modifier.pointerInput(item) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                latestOnEditStarted()
                                workingOrder = latestOrder
                                dragged = item
                                dragX = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()

                                val actualBounds =
                                    bounds[item] ?: return@detectDragGesturesAfterLongPress
                                val minOffset = -actualBounds.start
                                val maxOffset =
                                    (rowWidthPx - actualBounds.start - actualBounds.size)
                                        .coerceAtLeast(minOffset)
                                dragX =
                                    (dragX + amount.x)
                                        .coerceIn(minOffset, maxOffset)

                                val index = workingOrder.indexOf(item)
                                if (index < 0) return@detectDragGesturesAfterLongPress

                                val visualCenter = actualBounds.center + dragX
                                var targetIndex = index

                                if (index > 0) {
                                    val previous = workingOrder[index - 1]
                                    val previousStart = slotStart(previous, workingOrder)
                                    val previousSize = bounds[previous]?.size
                                    if (
                                        previousStart != null &&
                                        previousSize != null &&
                                        visualCenter < previousStart + previousSize / 2f
                                    ) {
                                        targetIndex = index - 1
                                    }
                                }

                                if (targetIndex == index && index < workingOrder.lastIndex) {
                                    val next = workingOrder[index + 1]
                                    val nextStart = slotStart(next, workingOrder)
                                    val nextSize = bounds[next]?.size
                                    if (
                                        nextStart != null &&
                                        nextSize != null &&
                                        visualCenter > nextStart + nextSize / 2f
                                    ) {
                                        targetIndex = index + 1
                                    }
                                }

                                if (targetIndex != index) {
                                    val moved = workingOrder.toMutableList()
                                    moved.removeAt(index)
                                    moved.add(targetIndex, item)
                                    workingOrder = moved
                                }
                            },
                            onDragEnd = {
                                val settled = workingOrder
                                latestOnOrderChange(settled)
                                latestOnOrderSettled(settled)
                                dragged = null
                                dragX = 0f
                            },
                            onDragCancel = {
                                dragged = null
                                dragX = 0f
                                workingOrder = latestOrder
                            },
                        )
                    }

                Box(
                    modifier =
                        Modifier
                            .weight(weightFor(item))
                            .onGloballyPositioned { coordinates ->
                                bounds[item] =
                                    AxisBounds(
                                        start = coordinates.positionInParent().x,
                                        size = coordinates.size.width.toFloat(),
                                    )
                            }
                            .offset {
                                IntOffset(
                                    x =
                                        (
                                            when {
                                                selected -> dragX
                                                dragged == null && order == workingOrder -> 0f
                                                else -> animatedOffset
                                            }
                                        ).roundToInt(),
                                    y = 0,
                                )
                            }
                            .then(if (dragHandleOnly) Modifier else dragGesture)
                            .zIndex(if (selected) 4f else 0f),
                    contentAlignment = Alignment.Center,
                ) {
                    content(item)

                    if (dragHandleOnly) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .width(34.dp)
                                    .height(16.dp)
                                    .then(dragGesture),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(
                                Modifier
                                    .width(18.dp)
                                    .height(3.dp)
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

private enum class ArtworkResizeHandle {
    LEFT,
    RIGHT,
    BOTTOM,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

/**
 * Long-press the artwork to expose phone-widget-style resize handles.
 *
 * The resize changes the real container size, not a visual scale transform, so every block below it
 * is remeasured and pushed away instead of being covered.
 */
@Composable
internal fun CapsuleLightResizableArtwork(
    baseSide: Dp,
    widthScale: Float,
    heightScale: Float,
    maxHeightScale: Float = 1.35f,
    editable: Boolean,
    onEditStarted: () -> Unit,
    onResizeSettled: (widthScale: Float, heightScale: Float) -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    var selected by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }
    var currentWidth by remember {
        mutableFloatStateOf(widthScale.coerceIn(0.55f, 1.08f))
    }
    var currentHeight by remember {
        mutableFloatStateOf(
            heightScale.coerceIn(
                0.55f,
                maxHeightScale.coerceAtLeast(0.55f),
            ),
        )
    }

    // External persisted state may change after reset/reload, but never replace the live state
    // object while a resize gesture is running.
    LaunchedEffect(widthScale, heightScale, maxHeightScale) {
        if (!resizing) {
            currentWidth = widthScale.coerceIn(0.55f, 1.08f)
            currentHeight =
                heightScale.coerceIn(
                    0.55f,
                    maxHeightScale.coerceAtLeast(0.55f),
                )
        }
    }

    LaunchedEffect(editable) {
        if (!editable) {
            selected = false
            resizing = false
        }
    }

    Box(
        modifier =
            modifier
                .width(baseSide * currentWidth)
                .height(baseSide * currentHeight)
                .then(
                    if (!editable) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    selected = true
                                },
                                onDrag = { change, _ -> change.consume() },
                            )
                        }
                    },
                )
                .then(
                    if (selected && editable) {
                        Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
                            RoundedCornerShape(18.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = contentAlignment,
    ) {
        content()

        if (selected && editable) {
            ArtworkResizeHandle.entries.forEach { handle ->
                CapsuleArtworkResizeHandle(
                    handle = handle,
                    baseSide = baseSide,
                    widthScale = currentWidth,
                    heightScale = currentHeight,
                    maxHeightScale = maxHeightScale,
                    onEditStarted = {
                        resizing = true
                        onEditStarted()
                    },
                    onResize = { w, h ->
                        // Local-only preview: no DataStore/parent-state writes per pointer sample.
                        currentWidth = w
                        currentHeight = h
                    },
                    onResizeSettled = {
                        resizing = false
                        onResizeSettled(currentWidth, currentHeight)
                    },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.CapsuleArtworkResizeHandle(
    handle: ArtworkResizeHandle,
    baseSide: Dp,
    widthScale: Float,
    heightScale: Float,
    maxHeightScale: Float,
    onEditStarted: () -> Unit,
    onResize: (Float, Float) -> Unit,
    onResizeSettled: () -> Unit,
) {
    val latestWidthScale by rememberUpdatedState(widthScale)
    val latestHeightScale by rememberUpdatedState(heightScale)

    val alignment =
        when (handle) {
            ArtworkResizeHandle.LEFT -> Alignment.CenterStart
            ArtworkResizeHandle.RIGHT -> Alignment.CenterEnd
            ArtworkResizeHandle.BOTTOM -> Alignment.BottomCenter
            ArtworkResizeHandle.BOTTOM_LEFT -> Alignment.BottomStart
            ArtworkResizeHandle.BOTTOM_RIGHT -> Alignment.BottomEnd
        }

    Box(
        modifier =
            Modifier
                .align(alignment)
                .size(34.dp)
                .pointerInput(handle, baseSide) {
                    var w = latestWidthScale
                    var h = latestHeightScale
                    val basePx = baseSide.toPx().coerceAtLeast(1f)
                    detectDragGestures(
                        onDragStart = {
                            w = latestWidthScale
                            h = latestHeightScale
                            onEditStarted()
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            when (handle) {
                                ArtworkResizeHandle.LEFT -> {
                                    w = (w - amount.x / basePx).coerceIn(0.55f, 1.08f)
                                }
                                ArtworkResizeHandle.RIGHT -> {
                                    w = (w + amount.x / basePx).coerceIn(0.55f, 1.08f)
                                }
                                ArtworkResizeHandle.BOTTOM -> {
                                    h =
                                        (h + amount.y / basePx).coerceIn(
                                            0.55f,
                                            maxHeightScale.coerceAtLeast(0.55f),
                                        )
                                }
                                ArtworkResizeHandle.BOTTOM_LEFT -> {
                                    w = (w - amount.x / basePx).coerceIn(0.55f, 1.08f)
                                    h =
                                        (h + amount.y / basePx).coerceIn(
                                            0.55f,
                                            maxHeightScale.coerceAtLeast(0.55f),
                                        )
                                }
                                ArtworkResizeHandle.BOTTOM_RIGHT -> {
                                    w = (w + amount.x / basePx).coerceIn(0.55f, 1.08f)
                                    h =
                                        (h + amount.y / basePx).coerceIn(
                                            0.55f,
                                            maxHeightScale.coerceAtLeast(0.55f),
                                        )
                                }
                            }
                            onResize(w, h)
                        },
                        onDragEnd = onResizeSettled,
                        onDragCancel = onResizeSettled,
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(11.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(
                    2.dp,
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    CircleShape,
                ),
        )
    }
}
