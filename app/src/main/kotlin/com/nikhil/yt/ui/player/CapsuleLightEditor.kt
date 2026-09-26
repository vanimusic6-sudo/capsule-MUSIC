package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
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

internal fun lightRowCrossedBefore(
    commandCenterPx: Float,
    neighbourCenterPx: Float,
): Boolean = commandCenterPx <= neighbourCenterPx + 0.5f

internal fun lightRowCrossedAfter(
    commandCenterPx: Float,
    neighbourCenterPx: Float,
): Boolean = commandCenterPx >= neighbourCenterPx - 0.5f

private val CapsuleLightDockGap = 8.dp
private val CapsuleLightDockThreshold = 52.dp
private val CapsuleLightPreferredDockThreshold = 82.dp
private val CapsuleLightEmptyAnchorFirst = 96.dp
private val CapsuleLightEmptyAnchorStep = 24.dp

private data class CapsuleLightDropPreview(
    val order: List<CapsuleLightBlock>,
    val gapsDp: Map<CapsuleLightBlock, Float>,
    val topPx: Float,
    val heightPx: Float,
    val usesEmptyCell: Boolean,
)

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
    onGapsSettled: (Map<CapsuleLightBlock, Float>) -> Unit = {},
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
    val latestOnGapsSettled by rememberUpdatedState(onGapsSettled)
    val latestOnGapsNormalized by rememberUpdatedState(onGapsNormalized)
    val latestOnEditStarted by rememberUpdatedState(onEditStarted)
    val density = LocalDensity.current

    val bounds = remember { mutableStateMapOf<CapsuleLightBlock, AxisBounds>() }
    var dragged by remember { mutableStateOf<CapsuleLightBlock?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var workingOrder by remember { mutableStateOf(order) }
    var dropPreview by remember { mutableStateOf<CapsuleLightDropPreview?>(null) }

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
                                    ((gapsDp[block] ?: 0f).coerceAtLeast(0f)).dp.toPx()
                                }
                            ((bounds[block]?.size ?: 0f) - rawGapPx)
                                .coerceAtLeast(0f)
                                .toDouble()
                        }.toFloat()
                    )
                    .coerceAtLeast(0f)

            val dockGapPx = with(density) { CapsuleLightDockGap.toPx() }
            val dockThresholdPx = with(density) { CapsuleLightDockThreshold.toPx() }
            val cellStepPx = with(density) { CapsuleLightEmptyAnchorStep.toPx() }

            buildMap {
                latestOrder.forEachIndexed { index, block ->
                    val rawRequestedPx =
                        with(density) {
                            ((gapsDp[block] ?: 0f).coerceAtLeast(0f)).dp.toPx()
                        }
                    val canonicalRequestedPx =
                        when {
                            rawRequestedPx <= 0.5f -> 0f
                            index == 0 && rawRequestedPx <= dockThresholdPx -> 0f
                            rawRequestedPx <= dockThresholdPx -> dockGapPx
                            else ->
                                kotlin.math.round(rawRequestedPx / cellStepPx) *
                                    cellStepPx
                        }
                    val acceptedPx =
                        canonicalRequestedPx.coerceAtMost(remainingGapPx)
                    put(block, acceptedPx / density.density)
                    remainingGapPx =
                        (remainingGapPx - acceptedPx).coerceAtLeast(0f)
                }
            }
        }

    LaunchedEffect(effectiveGapsDp, gapsDp, allBlocksMeasured) {
        if (
            allBlocksMeasured &&
            latestOrder.any { block ->
                kotlin.math.abs(
                    (effectiveGapsDp[block] ?: 0f) - (gapsDp[block] ?: 0f),
                ) > 0.05f
            }
        ) {
            latestOnGapsNormalized(effectiveGapsDp)
        }
    }

    fun gapPxFor(
        item: CapsuleLightBlock,
        source: Map<CapsuleLightBlock, Float>,
    ): Float =
        with(density) {
            ((source[item] ?: 0f).coerceAtLeast(0f)).dp.toPx()
        }

    fun contentHeightFor(item: CapsuleLightBlock): Float {
        val measured = bounds[item]?.size ?: 0f
        val gap = gapPxFor(item, effectiveGapsDp)
        return (measured - gap).coerceAtLeast(1f)
    }

    fun preferredDockPair(
        neighbour: CapsuleLightBlock?,
        moving: CapsuleLightBlock,
    ): Boolean {
        if (neighbour == null) return false
        if (neighbour == moving) return false

        val pair = setOf(neighbour, moving)
        return pair == setOf(CapsuleLightBlock.ARTWORK, CapsuleLightBlock.LYRIC) ||
            pair == setOf(CapsuleLightBlock.ARTWORK, CapsuleLightBlock.METADATA) ||
            pair == setOf(CapsuleLightBlock.LYRIC, CapsuleLightBlock.METADATA) ||
            pair == setOf(CapsuleLightBlock.METADATA, CapsuleLightBlock.PROGRESS) ||
            pair == setOf(CapsuleLightBlock.PROGRESS, CapsuleLightBlock.MODE_SWITCH) ||
            pair == setOf(CapsuleLightBlock.MODE_SWITCH, CapsuleLightBlock.CONTROLS)
    }

    fun resolveDropPreview(
        block: CapsuleLightBlock,
        settled: List<CapsuleLightBlock>,
        desiredTopPx: Float,
    ): CapsuleLightDropPreview {
        val requested = effectiveGapsDp.toMutableMap()
        requested[block] = 0f

        val settledIndex = settled.indexOf(block).coerceAtLeast(0)
        var baseTopPx = 0f
        if (settledIndex > 0) {
            for (index in 0 until settledIndex) {
                val item = settled[index]
                baseTopPx += gapPxFor(item, requested)
                baseTopPx += contentHeightFor(item)
            }
        }

        val contentHeightPx = contentHeightFor(block)
        val dockGapPx = with(density) { CapsuleLightDockGap.toPx() }
        val normalDockThresholdPx = with(density) { CapsuleLightDockThreshold.toPx() }
        val preferredDockThresholdPx =
            with(density) { CapsuleLightPreferredDockThreshold.toPx() }
        val emptyFirstPx = with(density) { CapsuleLightEmptyAnchorFirst.toPx() }
        val emptyStepPx = with(density) { CapsuleLightEmptyAnchorStep.toPx() }

        val previous = settled.getOrNull(settledIndex - 1)
        val next = settled.getOrNull(settledIndex + 1)
        val previousDockGapPx = if (settledIndex == 0) 0f else dockGapPx
        val previousDockTopPx = baseTopPx + previousDockGapPx
        val previousThresholdPx =
            if (preferredDockPair(previous, block)) {
                preferredDockThresholdPx
            } else {
                normalDockThresholdPx
            }
        val previousDistance =
            kotlin.math.abs(desiredTopPx - previousDockTopPx)

        val availableFreePx =
            if (next != null) {
                gapPxFor(next, effectiveGapsDp)
            } else {
                (viewportHeightPx - baseTopPx).coerceAtLeast(0f)
            }

        // If there is already real free space before the next element, the moving element may
        // dock to the next element's upper edge as well. This is a real-component target, not an
        // empty-cell target, so it has the same priority advantage.
        val nextDockGapPx =
            if (next != null) {
                (
                    availableFreePx -
                        contentHeightPx -
                        dockGapPx
                    ).coerceAtLeast(0f)
            } else {
                null
            }
        val nextDockTopPx =
            nextDockGapPx?.let { baseTopPx + it }
        val nextThresholdPx =
            if (preferredDockPair(next, block)) {
                preferredDockThresholdPx
            } else {
                normalDockThresholdPx
            }
        val nextDistance =
            nextDockTopPx?.let { kotlin.math.abs(desiredTopPx - it) }
                ?: Float.POSITIVE_INFINITY

        val rawGapPx = (desiredTopPx - baseTopPx).coerceAtLeast(0f)
        val snappedCellGapPx =
            kotlin.math.round(rawGapPx / emptyStepPx) * emptyStepPx
        val emptyCellFits =
            snappedCellGapPx >= emptyFirstPx &&
                snappedCellGapPx + contentHeightPx <= availableFreePx + 0.5f

        val previousDockWins =
            previousDistance <= previousThresholdPx &&
                previousDistance <= nextDistance
        val nextDockWins =
            nextDockTopPx != null &&
                nextDistance <= nextThresholdPx &&
                nextDistance < previousDistance

        // A real neighbour wins first. Only when neither real edge is close enough may the
        // selected empty cell become the destination.
        val usesEmptyCell =
            !previousDockWins && !nextDockWins && emptyCellFits
        val anchoredGapPx =
            when {
                previousDockWins -> previousDockGapPx
                nextDockWins -> nextDockGapPx ?: previousDockGapPx
                usesEmptyCell -> snappedCellGapPx
                previousDistance <= nextDistance -> previousDockGapPx
                else -> nextDockGapPx ?: previousDockGapPx
            }

        if (next != null) {
            val oldNextGapPx = gapPxFor(next, effectiveGapsDp)
            var remainder =
                (
                    oldNextGapPx -
                        anchoredGapPx -
                        contentHeightPx
                    ).coerceAtLeast(0f)

            if (remainder in 0.5f..normalDockThresholdPx) {
                remainder = dockGapPx
            } else if (remainder > normalDockThresholdPx) {
                remainder =
                    kotlin.math.round(remainder / emptyStepPx) * emptyStepPx
            }

            requested[next] = remainder / density.density
        }

        requested[block] = anchoredGapPx / density.density

        val totalContentPx =
            settled.sumOf { item ->
                contentHeightFor(item).toDouble()
            }.toFloat()
        var remainingFreePx =
            (viewportHeightPx - totalContentPx).coerceAtLeast(0f)

        // The moving block gets first claim on the selected target. Other old gaps are allowed
        // to shrink, never to push a real block outside the canvas.
        val normalized =
            requested.toMutableMap().apply {
                val movedRequestedPx =
                    gapPxFor(block, requested).coerceAtMost(remainingFreePx)
                this[block] = movedRequestedPx / density.density
                remainingFreePx =
                    (remainingFreePx - movedRequestedPx).coerceAtLeast(0f)

                settled.forEach { item ->
                    if (item == block) return@forEach
                    val requestedPx = gapPxFor(item, requested)
                    val acceptedPx = requestedPx.coerceAtMost(remainingFreePx)
                    this[item] = acceptedPx / density.density
                    remainingFreePx =
                        (remainingFreePx - acceptedPx).coerceAtLeast(0f)
                }
            }.toMap()

        var finalGaps = normalized
        var finalUsesEmptyCell = usesEmptyCell

        if (settled.lastOrNull() == block) {
            val otherGapPx =
                settled
                    .filterNot { it == block }
                    .sumOf { item ->
                        gapPxFor(item, normalized).toDouble()
                    }.toFloat()
            val maxMovedGapPx =
                (
                    viewportHeightPx -
                        totalContentPx -
                        otherGapPx
                    ).coerceAtLeast(0f)
            val bottomDockTopPx =
                (viewportHeightPx - contentHeightPx).coerceAtLeast(0f)
            val bottomDistance =
                kotlin.math.abs(desiredTopPx - bottomDockTopPx)

            if (bottomDistance <= normalDockThresholdPx) {
                finalGaps =
                    normalized.toMutableMap().apply {
                        this[block] = maxMovedGapPx / density.density
                    }.toMap()
                finalUsesEmptyCell = false
            }
        }

        var finalTopPx = 0f
        settled.forEach { item ->
            finalTopPx += gapPxFor(item, finalGaps)
            if (item == block) return@forEach
            finalTopPx += contentHeightFor(item)
        }

        // The loop above keeps accumulating after the moving item because return@forEach only
        // skips that iteration. Recompute in a tiny deterministic loop so top means exactly the
        // selected block's top.
        finalTopPx = 0f
        for (item in settled) {
            finalTopPx += gapPxFor(item, finalGaps)
            if (item == block) break
            finalTopPx += contentHeightFor(item)
        }

        return CapsuleLightDropPreview(
            order = settled,
            gapsDp = finalGaps,
            topPx =
                finalTopPx.coerceIn(
                    0f,
                    (viewportHeightPx - contentHeightPx).coerceAtLeast(0f),
                ),
            heightPx = contentHeightPx,
            usesEmptyCell = finalUsesEmptyCell,
        )
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

    Box(
        modifier =
            modifier
                .height(viewportHeight)
                .clipToBounds(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().height(viewportHeight),
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
                        draggedItem = null,
                        gapDeltaPx = 0f,
                    )
                val rawNeighbourTarget =
                    if (!selected && actual != null && virtualStart != null) {
                        virtualStart - actual.start
                    } else {
                        0f
                    }
                val neighbourTarget =
                    if (!selected && actual != null) {
                        val gapPx = with(density) { gap.dp.toPx() }
                        val contentTop = actual.start + gapPx
                        val contentHeight =
                            (actual.size - gapPx).coerceAtLeast(1f)
                        val minOffset = -contentTop
                        val maxOffset =
                            (viewportHeightPx - contentTop - contentHeight)
                                .coerceAtLeast(minOffset)
                        rawNeighbourTarget.coerceIn(minOffset, maxOffset)
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

                val selectedContentTop =
                    if (selected && actual != null) {
                        actual.start + with(density) { gap.dp.toPx() }
                    } else {
                        0f
                    }
                val selectedTargetOffset =
                    if (selected && dropPreview != null) {
                        dropPreview!!.topPx - selectedContentTop
                    } else {
                        dragOffsetY
                    }
                val selectedVisualOffset =
                    if (selected && dropPreview != null && !dropPreview!!.usesEmptyCell) {
                        // The thing under the finger is the thing that moves toward the fixed
                        // neighbour. The fixed neighbour never chases the finger.
                        dragOffsetY * 0.28f + selectedTargetOffset * 0.72f
                    } else {
                        dragOffsetY
                    }

                if (gap > 0f) {
                    CapsuleLightEmptyAnchorGap(
                        height = gap.dp,
                        highlight = gap.dp >= CapsuleLightDockThreshold,
                    )
                }

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
                            .graphicsLayer {
                                translationY =
                                    when {
                                        selected -> selectedVisualOffset
                                        dragged == null && order == workingOrder -> 0f
                                        else -> animatedNeighbourOffset
                                    }
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
                                            dropPreview = null
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

                                            val visualCenter =
                                                contentStart +
                                                    contentHeight / 2f +
                                                    dragOffsetY
                                            var targetIndex = currentIndex

                                            if (currentIndex > 0) {
                                                val previous = workingOrder[currentIndex - 1]
                                                val previousStart =
                                                    slotStart(
                                                        previous,
                                                        workingOrder,
                                                        null,
                                                        0f,
                                                    )
                                                val previousBounds = bounds[previous]
                                                val previousGapPx =
                                                    gapPxFor(previous, effectiveGapsDp)
                                                val previousContentHeight =
                                                    (
                                                        (previousBounds?.size ?: 0f) -
                                                            previousGapPx
                                                        ).coerceAtLeast(1f)
                                                val previousCenter =
                                                    previousStart?.let {
                                                        it +
                                                            previousGapPx +
                                                            previousContentHeight / 2f
                                                    }
                                                if (
                                                    previousCenter != null &&
                                                    visualCenter < previousCenter
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
                                                        null,
                                                        0f,
                                                    )
                                                val nextBounds = bounds[next]
                                                val nextGapPx =
                                                    gapPxFor(next, effectiveGapsDp)
                                                val nextContentHeight =
                                                    (
                                                        (nextBounds?.size ?: 0f) -
                                                            nextGapPx
                                                        ).coerceAtLeast(1f)
                                                val nextCenter =
                                                    nextStart?.let {
                                                        it +
                                                            nextGapPx +
                                                            nextContentHeight / 2f
                                                    }
                                                if (
                                                    nextCenter != null &&
                                                    visualCenter > nextCenter
                                                ) {
                                                    targetIndex = currentIndex + 1
                                                }
                                            }

                                            val candidateOrder =
                                                if (targetIndex != currentIndex) {
                                                    workingOrder.toMutableList().apply {
                                                        removeAt(currentIndex)
                                                        add(targetIndex, block)
                                                    }.also { workingOrder = it }
                                                } else {
                                                    workingOrder
                                                }

                                            val desiredTopPx =
                                                (contentStart + dragOffsetY)
                                                    .coerceIn(
                                                        0f,
                                                        (viewportHeightPx - contentHeight)
                                                            .coerceAtLeast(0f),
                                                    )
                                            dropPreview =
                                                resolveDropPreview(
                                                    block = block,
                                                    settled = candidateOrder,
                                                    desiredTopPx = desiredTopPx,
                                                )
                                        },
                                        onDragEnd = {
                                            val preview =
                                                dropPreview
                                                    ?: run {
                                                        val actualBounds = bounds[block]
                                                        val gapPx =
                                                            with(density) {
                                                                ((effectiveGapsDp[block] ?: 0f)
                                                                    .coerceAtLeast(0f)).dp.toPx()
                                                            }
                                                        val contentHeight =
                                                            (
                                                                (actualBounds?.size ?: 0f) -
                                                                    gapPx
                                                                ).coerceAtLeast(1f)
                                                        val desiredTopPx =
                                                            (
                                                                (actualBounds?.start ?: 0f) +
                                                                    gapPx +
                                                                    dragOffsetY
                                                                ).coerceIn(
                                                                    0f,
                                                                    (
                                                                        viewportHeightPx -
                                                                            contentHeight
                                                                        ).coerceAtLeast(0f),
                                                                )
                                                        resolveDropPreview(
                                                            block = block,
                                                            settled = workingOrder,
                                                            desiredTopPx = desiredTopPx,
                                                        )
                                                    }

                                            latestOnGapsSettled(preview.gapsDp)
                                            latestOnOrderChange(preview.order)
                                            latestOnOrderSettled(preview.order)
                                            dragged = null
                                            dragOffsetY = 0f
                                            dropPreview = null
                                        },
                                        onDragCancel = {
                                            dragged = null
                                            dragOffsetY = 0f
                                            workingOrder = latestOrder
                                            dropPreview = null
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

        if (allBlocksMeasured) {
            val committedEndPx =
                order.sumOf { item ->
                    (
                        gapPxFor(item, effectiveGapsDp) +
                            contentHeightFor(item)
                        ).toDouble()
                }.toFloat()
            val trailingPx =
                (viewportHeightPx - committedEndPx).coerceAtLeast(0f)
            if (trailingPx > 0.5f) {
                CapsuleLightEmptyAnchorGap(
                    height = with(density) { trailingPx.toDp() },
                    highlight =
                        trailingPx >=
                            with(density) { CapsuleLightDockThreshold.toPx() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = committedEndPx
                            },
                )
            }
        }

        val preview = dropPreview
        if (dragged != null && preview?.usesEmptyCell == true) {
            val previewHeight =
                with(density) {
                    preview.heightPx.toDp()
                }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .offset {
                            IntOffset(
                                0,
                                preview.topPx.roundToInt(),
                            )
                        }
                        .height(previewHeight)
                        .zIndex(2f)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.055f),
                            RoundedCornerShape(18.dp),
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.48f),
                            RoundedCornerShape(18.dp),
                        ),
            )
        }
    }
}
}

@Composable
private fun CapsuleLightEmptyAnchorGap(
    height: Dp,
    highlight: Boolean,
    modifier: Modifier = Modifier,
) {
    val guideColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height),
    ) {
        if (!highlight || height < CapsuleLightEmptyAnchorFirst) {
            return@Canvas
        }

        val firstPx = CapsuleLightEmptyAnchorFirst.toPx()
        val stepPx = CapsuleLightEmptyAnchorStep.toPx()
        val halfWidth = 35.dp.toPx()
        val stroke = 3.dp.toPx()

        var y = firstPx
        var count = 0
        while (y < size.height - 0.5f && count < 12) {
            drawLine(
                color = guideColor,
                start = Offset(size.width / 2f - halfWidth, y),
                end = Offset(size.width / 2f + halfWidth, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            y += stepPx
            count += 1
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
    dragHandleOnlyFor: ((T) -> Boolean)? = null,
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
                val handleOnly = dragHandleOnlyFor?.invoke(item) ?: dragHandleOnly
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
                                        lightRowCrossedBefore(
                                            commandCenterPx = visualCenter,
                                            neighbourCenterPx = previousStart + previousSize / 2f,
                                        )
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
                                        lightRowCrossedAfter(
                                            commandCenterPx = visualCenter,
                                            neighbourCenterPx = nextStart + nextSize / 2f,
                                        )
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
                            .graphicsLayer {
                                translationX =
                                    when {
                                        selected -> dragX
                                        dragged == null && order == workingOrder -> 0f
                                        else -> animatedOffset
                                    }
                            }
                            .then(if (handleOnly) Modifier else dragGesture)
                            .zIndex(if (selected) 4f else 0f),
                    contentAlignment = Alignment.Center,
                ) {
                    content(item)

                    if (handleOnly) {
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
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    LEFT,
    RIGHT,
    BOTTOM_LEFT,
    BOTTOM,
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
    onTopEdgeShiftDp: (Float) -> Unit = {},
    onResizeSettled: (widthScale: Float, heightScale: Float) -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    var selected by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }
    var pendingCommit by remember { mutableStateOf<Pair<Float, Float>?>(null) }
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
        if (resizing) return@LaunchedEffect

        val pending = pendingCommit
        if (pending != null) {
            val acceptedWidth = pending.first.coerceIn(0.55f, 1.08f)
            val acceptedHeight =
                pending.second.coerceIn(
                    0.55f,
                    maxHeightScale.coerceAtLeast(0.55f),
                )
            val widthArrived =
                kotlin.math.abs(widthScale - acceptedWidth) <= 0.005f
            val heightArrived =
                kotlin.math.abs(heightScale - acceptedHeight) <= 0.005f
            if (widthArrived && heightArrived) {
                currentWidth = acceptedWidth
                currentHeight = acceptedHeight
                pendingCommit = null
            } else if (
                acceptedWidth != pending.first ||
                acceptedHeight != pending.second
            ) {
                // The constraint canvas tightened the legal artwork size while the parent was
                // committing. Follow the accepted value without ever flashing through the old one.
                currentWidth = acceptedWidth
                currentHeight = acceptedHeight
                pendingCommit = acceptedWidth to acceptedHeight
            }
            // Until the parent catches up, keep the exact accepted local frame that the finger
            // produced. Never flash back through the previous saved size.
            return@LaunchedEffect
        }

        currentWidth = widthScale.coerceIn(0.55f, 1.08f)
        currentHeight =
            heightScale.coerceIn(
                0.55f,
                maxHeightScale.coerceAtLeast(0.55f),
            )
    }

    LaunchedEffect(editable) {
        if (!editable) {
            selected = false
            resizing = false
            pendingCommit = null
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
                    onTopEdgeShiftDp = onTopEdgeShiftDp,
                    onResize = { w, h ->
                        // Local-only preview: no DataStore/parent-state writes per pointer sample.
                        currentWidth = w
                        currentHeight = h
                    },
                    onResizeSettled = {
                        val finalWidth = currentWidth
                        val finalHeight = currentHeight
                        pendingCommit = finalWidth to finalHeight
                        // Parent commit first; only then release the local gesture
                        // latch. This removes the one-frame rollback on fast shrink.
                        onResizeSettled(finalWidth, finalHeight)
                        resizing = false
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
    onTopEdgeShiftDp: (Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    onResizeSettled: () -> Unit,
) {
    val latestWidthScale by rememberUpdatedState(widthScale)
    val latestHeightScale by rememberUpdatedState(heightScale)
    val latestMaxHeightScale by rememberUpdatedState(maxHeightScale)

    val alignment =
        when (handle) {
            ArtworkResizeHandle.TOP_LEFT -> Alignment.TopStart
            ArtworkResizeHandle.TOP -> Alignment.TopCenter
            ArtworkResizeHandle.TOP_RIGHT -> Alignment.TopEnd
            ArtworkResizeHandle.LEFT -> Alignment.CenterStart
            ArtworkResizeHandle.RIGHT -> Alignment.CenterEnd
            ArtworkResizeHandle.BOTTOM_LEFT -> Alignment.BottomStart
            ArtworkResizeHandle.BOTTOM -> Alignment.BottomCenter
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
                    var startHeightScale = latestHeightScale
                    val basePx = baseSide.toPx().coerceAtLeast(1f)
                    val topAnchored =
                        handle == ArtworkResizeHandle.TOP_LEFT ||
                            handle == ArtworkResizeHandle.TOP ||
                            handle == ArtworkResizeHandle.TOP_RIGHT
                    detectDragGestures(
                        onDragStart = {
                            w = latestWidthScale
                            h = latestHeightScale
                            startHeightScale = h
                            onEditStarted()
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            when (handle) {
                                ArtworkResizeHandle.TOP_LEFT -> {
                                    w = (w - amount.x / basePx).coerceIn(0.55f, 1.08f)
                                    h =
                                        (h - amount.y / basePx).coerceIn(
                                            0.55f,
                                            latestMaxHeightScale.coerceAtLeast(0.55f),
                                        )
                                }
                                ArtworkResizeHandle.TOP -> {
                                    h =
                                        (h - amount.y / basePx).coerceIn(
                                            0.55f,
                                            latestMaxHeightScale.coerceAtLeast(0.55f),
                                        )
                                }
                                ArtworkResizeHandle.TOP_RIGHT -> {
                                    w = (w + amount.x / basePx).coerceIn(0.55f, 1.08f)
                                    h =
                                        (h - amount.y / basePx).coerceIn(
                                            0.55f,
                                            latestMaxHeightScale.coerceAtLeast(0.55f),
                                        )
                                }
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
                                            latestMaxHeightScale.coerceAtLeast(0.55f),
                                        )
                                }
                                ArtworkResizeHandle.BOTTOM_LEFT -> {
                                    w = (w - amount.x / basePx).coerceIn(0.55f, 1.08f)
                                    h =
                                        (h + amount.y / basePx).coerceIn(
                                            0.55f,
                                            latestMaxHeightScale.coerceAtLeast(0.55f),
                                        )
                                }
                                ArtworkResizeHandle.BOTTOM_RIGHT -> {
                                    w = (w + amount.x / basePx).coerceIn(0.55f, 1.08f)
                                    h =
                                        (h + amount.y / basePx).coerceIn(
                                            0.55f,
                                            latestMaxHeightScale.coerceAtLeast(0.55f),
                                        )
                                }
                            }
                            if (topAnchored) {
                                // Keep the artwork's bottom edge stationary. Shrinking from the top
                                // moves ARTWORK down; growing from the top moves it up.
                                onTopEdgeShiftDp(
                                    (startHeightScale - h) * baseSide.value,
                                )
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
