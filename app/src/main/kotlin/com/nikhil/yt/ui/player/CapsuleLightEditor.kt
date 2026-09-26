package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Atomic Capsule Light elements.
 *
 * Six virtual columns are used by the clay layout. Small buttons occupy one column, AUDIO/VIDEO
 * occupy two each, play/pause occupies two, and content such as artwork/progress spans all six.
 * This is what lets controls leave their original group without ever becoming free x/y pixels.
 */
internal enum class CapsuleLightElement(
    val span: Int,
    val draggable: Boolean = true,
) {
    ARTWORK(6),
    LYRIC(6),
    TITLE(5),
    FAVORITE(1),
    ARTIST(6),
    PROGRESS(6),
    TIMES(6),

    SHUFFLE(1),
    AUDIO(2),
    VIDEO(2),
    SLEEP(1),

    /**
     * Fixed magnetic boundary between loose controls and the transport tray.
     * Dragging a control across it means taking the control out of / putting it into the tray.
     */
    PANEL_BREAK(6, draggable = false),

    REPEAT(1),
    PREVIOUS(1),
    PLAY_PAUSE(2),
    NEXT(1),
    MENU(1),
}

internal val CapsuleLightBaseOrder: List<CapsuleLightElement> = CapsuleLightElement.entries.toList()

internal val CapsuleLightBaseOrderEncoded: String =
    CapsuleLightBaseOrder.joinToString(",") { it.name }

internal fun decodeCapsuleLightOrder(raw: String): List<CapsuleLightElement> {
    if (raw.isBlank()) return CapsuleLightBaseOrder

    val parsed =
        raw.split(',')
            .mapNotNull { token ->
                runCatching { CapsuleLightElement.valueOf(token.trim()) }.getOrNull()
            }

    return if (
        parsed.size == CapsuleLightBaseOrder.size &&
        parsed.distinct().size == CapsuleLightBaseOrder.size &&
        parsed.toSet() == CapsuleLightBaseOrder.toSet()
    ) {
        parsed
    } else {
        // This also migrates the proof-of-concept five-block format to the atomic base safely.
        CapsuleLightBaseOrder
    }
}

internal fun encodeCapsuleLightOrder(order: List<CapsuleLightElement>): String =
    decodeCapsuleLightOrder(order.joinToString(",") { it.name })
        .joinToString(",") { it.name }

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

private const val CLAY_COLUMNS = 6

private data class ClayBounds(
    val rect: Rect,
) {
    val center: Offset
        get() = rect.center
}

private data class ClayRow(
    val indices: List<Int>,
    val usedColumns: Int,
)

/**
 * Small non-lazy grid: there are fewer than twenty Light atoms, so composing them all is cheaper
 * and, importantly, keeps Capsule's existing ScrollState/queue behaviour.
 */
@Composable
internal fun CapsuleLightClayLayout(
    order: List<CapsuleLightElement>,
    editable: Boolean,
    scrollState: ScrollState,
    onOrderChange: (List<CapsuleLightElement>) -> Unit,
    onOrderSettled: (List<CapsuleLightElement>) -> Unit,
    onEditStarted: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable (element: CapsuleLightElement, insidePanel: Boolean) -> Unit,
) {
    val latestOrder by rememberUpdatedState(order)
    val latestOnOrderChange by rememberUpdatedState(onOrderChange)
    val latestOnOrderSettled by rememberUpdatedState(onOrderSettled)
    val latestOnEditStarted by rememberUpdatedState(onEditStarted)

    val bounds = remember { mutableStateMapOf<CapsuleLightElement, ClayBounds>() }
    var dragged by remember { mutableStateOf<CapsuleLightElement?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var workingOrder by remember { mutableStateOf(order) }

    LaunchedEffect(order, dragged) {
        if (dragged == null) workingOrder = order
    }

    Box(
        modifier =
            modifier.verticalScroll(
                state = scrollState,
                enabled = dragged == null,
            ),
    ) {
        CapsuleLightSixColumnGrid(
            modifier = Modifier.fillMaxWidth(),
            elements = order,
        ) { element ->
            key(element) {
                val selected = dragged == element
                val panelIndex = order.indexOf(CapsuleLightElement.PANEL_BREAK)
                val insidePanel =
                    element != CapsuleLightElement.PANEL_BREAK &&
                        order.indexOf(element) > panelIndex

                CapsuleClayAnimatedElement(
                    element = element,
                    selected = selected,
                    editable = editable,
                    bounds = bounds,
                    dragOffset = Offset(dragX, dragY),
                    onSelectedBaseShift = { delta ->
                        if (selected) {
                            dragX += delta.x
                            dragY += delta.y
                        }
                    },
                    onDragStart = {
                        latestOnEditStarted()
                        workingOrder = latestOrder
                        dragged = element
                        dragX = 0f
                        dragY = 0f
                    },
                    onDrag = { amount ->
                        dragX += amount.x
                        dragY += amount.y

                        val current = bounds[element]?.rect ?: return@CapsuleClayAnimatedElement
                        val draggedCenter = current.center + Offset(dragX, dragY)

                        val target =
                            bounds
                                .filterKeys { it != element }
                                .minByOrNull { (_, candidate) ->
                                    val c = candidate.center
                                    hypot(
                                        (draggedCenter.x - c.x).toDouble(),
                                        (draggedCenter.y - c.y).toDouble(),
                                    )
                                }

                        if (target != null) {
                            val (targetElement, targetBounds) = target
                            val expanded =
                                targetBounds.rect.inflate(
                                    maxOf(targetBounds.rect.width, targetBounds.rect.height) * 0.22f,
                                )

                            if (draggedCenter in expanded) {
                                val from = workingOrder.indexOf(element)
                                var to = workingOrder.indexOf(targetElement)

                                if (from >= 0 && to >= 0 && from != to) {
                                    val moved = workingOrder.toMutableList()
                                    moved.removeAt(from)

                                    // Removing an earlier element shifts the insertion index by one.
                                    if (from < to) to -= 1

                                    if (targetElement == CapsuleLightElement.PANEL_BREAK) {
                                        val belowBreak = draggedCenter.y > targetBounds.center.y
                                        to =
                                            moved.indexOf(CapsuleLightElement.PANEL_BREAK) +
                                                if (belowBreak) 1 else 0
                                    }

                                    to = to.coerceIn(0, moved.size)
                                    moved.add(to, element)

                                    if (moved != workingOrder) {
                                        workingOrder = moved
                                        latestOnOrderChange(moved)
                                    }
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        val settled = workingOrder
                        dragged = null
                        dragX = 0f
                        dragY = 0f
                        latestOnOrderSettled(settled)
                    },
                    onDragCancel = {
                        dragged = null
                        dragX = 0f
                        dragY = 0f
                        latestOnOrderChange(workingOrder)
                        latestOnOrderSettled(workingOrder)
                    },
                ) {
                    content(element, insidePanel)
                }
            }
        }
    }
}

@Composable
private fun CapsuleClayAnimatedElement(
    element: CapsuleLightElement,
    selected: Boolean,
    editable: Boolean,
    bounds: MutableMap<CapsuleLightElement, ClayBounds>,
    dragOffset: Offset,
    onSelectedBaseShift: (Offset) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settleX = remember(element) { Animatable(0f) }
    val settleY = remember(element) { Animatable(0f) }
    var lastBase by remember(element) { mutableStateOf<Offset?>(null) }

    val scale by
        animateFloatAsState(
            targetValue = if (selected) 1.035f else 1f,
            animationSpec =
                spring(
                    dampingRatio = 0.68f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "capsuleClayLift",
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val next = coordinates.positionInRoot()
                    val previous = lastBase
                    lastBase = next

                    bounds[element] =
                        ClayBounds(
                            Rect(
                                offset = next,
                                size =
                                    Size(
                                        coordinates.size.width.toFloat(),
                                        coordinates.size.height.toFloat(),
                                    ),
                            ),
                        )

                    if (previous != null && previous != next) {
                        val delta = previous - next

                        if (selected) {
                            // The slot moved under the dragged view; counter it to keep finger lock.
                            onSelectedBaseShift(delta)
                        } else {
                            scope.launch {
                                settleX.snapTo(settleX.value + delta.x)
                                settleY.snapTo(settleY.value + delta.y)

                                launch {
                                    settleX.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = 0.72f,
                                            stiffness = Spring.StiffnessLow,
                                        ),
                                    )
                                }
                                launch {
                                    settleY.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = 0.72f,
                                            stiffness = Spring.StiffnessLow,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                .then(
                    if (!editable || !element.draggable) {
                        Modifier
                    } else {
                        Modifier.pointerInput(element) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onDragStart() },
                                onDrag = { change, amount ->
                                    change.consume()
                                    onDrag(amount)
                                },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel,
                            )
                        }
                    },
                )
                .zIndex(if (selected) 4f else 0f)
                .graphicsLayer {
                    translationX = if (selected) dragOffset.x else settleX.value
                    translationY = if (selected) dragOffset.y else settleY.value
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = if (selected) 14.dp.toPx() else 0f
                }
                .alpha(if (editable && !selected) 0.97f else 1f),
    ) {
        content()
    }
}

/**
 * Packs variable-width atoms into six magnetic columns.
 *
 * Row heights are measured from their tallest element, so moving the two-column orbit next to a
 * one-column button cannot overlap the row above or below.
 */
@Composable
private fun CapsuleLightSixColumnGrid(
    elements: List<CapsuleLightElement>,
    modifier: Modifier = Modifier,
    content: @Composable (CapsuleLightElement) -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            elements.forEach { element ->
                key(element) {
                    Box(Modifier.fillMaxWidth()) {
                        content(element)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val horizontalGap = 6.dp.roundToPx()
        val verticalGap = 8.dp.roundToPx()
        val maxWidth = constraints.maxWidth
        val cellWidth =
            ((maxWidth - horizontalGap * (CLAY_COLUMNS - 1)) / CLAY_COLUMNS)
                .coerceAtLeast(1)

        val rows = mutableListOf<ClayRow>()
        var current = mutableListOf<Int>()
        var used = 0

        elements.forEachIndexed { index, element ->
            val span = element.span.coerceIn(1, CLAY_COLUMNS)
            if (current.isNotEmpty() && used + span > CLAY_COLUMNS) {
                rows += ClayRow(current.toList(), used)
                current = mutableListOf()
                used = 0
            }
            current += index
            used += span
            if (used == CLAY_COLUMNS) {
                rows += ClayRow(current.toList(), used)
                current = mutableListOf()
                used = 0
            }
        }
        if (current.isNotEmpty()) rows += ClayRow(current.toList(), used)

        val placeables = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        val rowHeights = IntArray(rows.size)

        rows.forEachIndexed { rowIndex, row ->
            row.indices.forEach { index ->
                val span = elements[index].span.coerceIn(1, CLAY_COLUMNS)
                val width = cellWidth * span + horizontalGap * (span - 1)
                val placeable =
                    measurables[index].measure(
                        Constraints(
                            minWidth = width,
                            maxWidth = width,
                            minHeight = 0,
                            maxHeight = constraints.maxHeight,
                        ),
                    )
                placeables[index] = placeable
                rowHeights[rowIndex] = maxOf(rowHeights[rowIndex], placeable.height)
            }
        }

        val totalHeight =
            rowHeights.sum() +
                verticalGap * (rows.size - 1).coerceAtLeast(0)

        layout(maxWidth, totalHeight.coerceAtLeast(0)) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.indices.forEach { index ->
                    val placeable = placeables[index] ?: return@forEach
                    placeable.placeRelative(x, y)
                    x += placeable.width + horizontalGap
                }
                y += rowHeights[rowIndex] + verticalGap
            }
        }
    }
}
