package com.nikhil.yt.ui.player

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.zIndex

/**
 * The first "clay" surface is deliberately slot based.
 *
 * Nothing here stores arbitrary coordinates: the user can only reorder complete Light-player
 * blocks. That makes overlap impossible in the saved layout and gives us the same mental model as
 * moving icons on a phone home screen — crossing a neighbour moves that neighbour out of the way.
 */
internal enum class CapsuleLightBlock {
    ARTWORK,
    METADATA,
    PROGRESS,
    MODE_SWITCH,
    CONTROLS,
}

internal val CapsuleLightBaseOrder: List<CapsuleLightBlock> = CapsuleLightBlock.entries.toList()

internal val CapsuleLightBaseOrderEncoded: String =
    CapsuleLightBaseOrder.joinToString(",") { it.name }

internal fun decodeCapsuleLightOrder(raw: String): List<CapsuleLightBlock> {
    if (raw.isBlank()) return CapsuleLightBaseOrder

    val parsed =
        raw.split(',')
            .mapNotNull { token ->
                runCatching { CapsuleLightBlock.valueOf(token.trim()) }.getOrNull()
            }

    return if (
        parsed.size == CapsuleLightBaseOrder.size &&
        parsed.distinct().size == CapsuleLightBaseOrder.size &&
        parsed.toSet() == CapsuleLightBaseOrder.toSet()
    ) {
        parsed
    } else {
        CapsuleLightBaseOrder
    }
}

internal fun encodeCapsuleLightOrder(order: List<CapsuleLightBlock>): String =
    decodeCapsuleLightOrder(order.joinToString(",") { it.name })
        .joinToString(",") { it.name }

/**
 * A tiny process-local half of the crash guard.
 *
 * DataStore keeps a persistent "editing session is active" bit. The first Light editor rendered in
 * a fresh process checks that bit: if it was already set, the previous process died before editing
 * was finished and the layout is reset. We need this in-memory marker only to distinguish that
 * recovery case from the first legitimate render immediately after the user enables the editor.
 */
internal object CapsuleLightEditorProcessGuard {
    @Volatile
    private var startedInThisProcess = false

    @Synchronized
    fun begin(): Boolean {
        if (startedInThisProcess) return false
        startedInThisProcess = true
        return true
    }

    @Synchronized
    fun end() {
        startedInThisProcess = false
    }
}

private data class BlockBounds(
    val top: Float,
    val height: Float,
) {
    val center: Float get() = top + height / 2f
}

/**
 * Reorder-only editor for Capsule Light.
 *
 * Long press lifts one block. Crossing the centre of the adjacent block swaps the two slots.
 * The Column itself always owns the real positions, so no saved state can make two blocks overlap.
 */
@Composable
internal fun CapsuleLightReorderColumn(
    order: List<CapsuleLightBlock>,
    editable: Boolean,
    scrollState: ScrollState,
    onOrderChange: (List<CapsuleLightBlock>) -> Unit,
    onOrderSettled: (List<CapsuleLightBlock>) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (CapsuleLightBlock) -> Unit,
) {
    val latestOrder by rememberUpdatedState(order)
    val latestOnOrderChange by rememberUpdatedState(onOrderChange)
    val latestOnOrderSettled by rememberUpdatedState(onOrderSettled)

    val bounds = remember { mutableStateMapOf<CapsuleLightBlock, BlockBounds>() }
    var dragged by remember { mutableStateOf<CapsuleLightBlock?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var lastBaseTop by remember { mutableFloatStateOf(Float.NaN) }
    var workingOrder by remember { mutableStateOf(order) }

    LaunchedEffect(order, dragged) {
        if (dragged == null) workingOrder = order
    }

    Column(
        modifier =
            modifier.verticalScroll(
                state = scrollState,
                enabled = dragged == null,
            ),
    ) {
        order.forEach { block ->
            val selected = dragged == block

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            val newTop = coordinates.positionInParent().y
                            val newBounds =
                                BlockBounds(
                                    top = newTop,
                                    height = coordinates.size.height.toFloat(),
                                )
                            bounds[block] = newBounds

                            /*
                             * A swap causes the Column to assign the dragged block a new base slot.
                             * Counter that base movement so the block remains under the finger
                             * instead of jumping when its neighbours move around it.
                             */
                            if (selected) {
                                if (!lastBaseTop.isNaN()) {
                                    dragOffsetY += lastBaseTop - newTop
                                }
                                lastBaseTop = newTop
                            }
                        }
                        .then(
                            if (!editable) {
                                Modifier
                            } else {
                                Modifier.pointerInput(block) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragged = block
                                            workingOrder = latestOrder
                                            dragOffsetY = 0f
                                            lastBaseTop = bounds[block]?.top ?: Float.NaN
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y

                                            val currentIndex = workingOrder.indexOf(block)
                                            val currentBounds = bounds[block]
                                            if (currentIndex < 0 || currentBounds == null) {
                                                return@detectDragGesturesAfterLongPress
                                            }

                                            val draggedCenter = currentBounds.center + dragOffsetY
                                            var targetIndex = currentIndex

                                            if (currentIndex > 0) {
                                                val previous = workingOrder[currentIndex - 1]
                                                val previousCenter = bounds[previous]?.center
                                                if (previousCenter != null && draggedCenter < previousCenter) {
                                                    targetIndex = currentIndex - 1
                                                }
                                            }

                                            if (targetIndex == currentIndex && currentIndex < workingOrder.lastIndex) {
                                                val next = workingOrder[currentIndex + 1]
                                                val nextCenter = bounds[next]?.center
                                                if (nextCenter != null && draggedCenter > nextCenter) {
                                                    targetIndex = currentIndex + 1
                                                }
                                            }

                                            if (targetIndex != currentIndex) {
                                                val moved = workingOrder.toMutableList()
                                                moved.removeAt(currentIndex)
                                                moved.add(targetIndex, block)
                                                workingOrder = moved
                                                latestOnOrderChange(moved)
                                            }
                                        },
                                        onDragEnd = {
                                            val settled = workingOrder
                                            dragged = null
                                            dragOffsetY = 0f
                                            lastBaseTop = Float.NaN
                                            latestOnOrderSettled(settled)
                                        },
                                        onDragCancel = {
                                            dragged = null
                                            dragOffsetY = 0f
                                            lastBaseTop = Float.NaN
                                            latestOnOrderChange(workingOrder)
                                            latestOnOrderSettled(workingOrder)
                                        },
                                    )
                                }
                            },
                        )
                        .zIndex(if (selected) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (selected) dragOffsetY else 0f
                        }
                        .scale(if (selected) 1.015f else 1f)
                        .alpha(if (editable && !selected) 0.94f else 1f),
            ) {
                content(block)
            }
        }
    }
}
