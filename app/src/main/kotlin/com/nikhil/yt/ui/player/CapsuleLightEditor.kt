package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * Capsule Light editor v3: hierarchy, not a flat grid.
 *
 * The outer level reorders the original Light containers. Inner rows only reorder the controls
 * that already belong to that container. This keeps the real Light visual language intact.
 */
internal enum class CapsuleLightBlock {
    ARTWORK,
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

internal fun decodeCapsuleLightOrder(raw: String) = decodeEnumOrder(raw, CapsuleLightBaseOrder)
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
 * Outer Light containers. The little grip is only visible while edit mode is enabled, so nested
 * controls can own long-press without fighting the parent gesture.
 */
@Composable
internal fun CapsuleLightReorderColumn(
    order: List<CapsuleLightBlock>,
    editable: Boolean,
    scrollState: ScrollState,
    onOrderChange: (List<CapsuleLightBlock>) -> Unit,
    onOrderSettled: (List<CapsuleLightBlock>) -> Unit,
    onEditStarted: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable (CapsuleLightBlock) -> Unit,
) {
    val latestOrder by rememberUpdatedState(order)
    val latestOnOrderChange by rememberUpdatedState(onOrderChange)
    val latestOnOrderSettled by rememberUpdatedState(onOrderSettled)
    val latestOnEditStarted by rememberUpdatedState(onEditStarted)

    val bounds = remember { mutableStateMapOf<CapsuleLightBlock, AxisBounds>() }
    var dragged by remember { mutableStateOf<CapsuleLightBlock?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
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
            key(block) {
                val selected = dragged == block
                CapsuleSoftColumnItem(
                    id = block,
                    selected = selected,
                    dragOffsetY = dragOffsetY,
                    bounds = bounds,
                    onSelectedBaseShift = { dragOffsetY += it },
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        content(block)

                        if (editable) {
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
                                                    dragOffsetY += amount.y

                                                    val currentIndex = workingOrder.indexOf(block)
                                                    val currentBounds = bounds[block]
                                                    if (currentIndex < 0 || currentBounds == null) {
                                                        return@detectDragGesturesAfterLongPress
                                                    }

                                                    val center = currentBounds.center + dragOffsetY
                                                    var target = currentIndex

                                                    if (currentIndex > 0) {
                                                        val previous = workingOrder[currentIndex - 1]
                                                        val previousCenter = bounds[previous]?.center
                                                        if (previousCenter != null && center < previousCenter) {
                                                            target = currentIndex - 1
                                                        }
                                                    }

                                                    if (target == currentIndex && currentIndex < workingOrder.lastIndex) {
                                                        val next = workingOrder[currentIndex + 1]
                                                        val nextCenter = bounds[next]?.center
                                                        if (nextCenter != null && center > nextCenter) {
                                                            target = currentIndex + 1
                                                        }
                                                    }

                                                    if (target != currentIndex) {
                                                        val moved = workingOrder.toMutableList()
                                                        moved.removeAt(currentIndex)
                                                        moved.add(target, block)
                                                        workingOrder = moved
                                                        latestOnOrderChange(moved)
                                                    }
                                                },
                                                onDragEnd = {
                                                    val settled = workingOrder
                                                    dragged = null
                                                    dragOffsetY = 0f
                                                    latestOnOrderSettled(settled)
                                                },
                                                onDragCancel = {
                                                    dragged = null
                                                    dragOffsetY = 0f
                                                    latestOnOrderChange(workingOrder)
                                                    latestOnOrderSettled(workingOrder)
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
}

@Composable
private fun CapsuleSoftColumnItem(
    id: CapsuleLightBlock,
    selected: Boolean,
    dragOffsetY: Float,
    bounds: MutableMap<CapsuleLightBlock, AxisBounds>,
    onSelectedBaseShift: (Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settle = remember(id) { Animatable(0f) }
    var lastBase by remember(id) { mutableFloatStateOf(Float.NaN) }
    val scale by
        animateFloatAsState(
            targetValue = if (selected) 1.018f else 1f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "capsuleBlockLift",
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val next = coordinates.positionInParent().y
                    bounds[id] = AxisBounds(next, coordinates.size.height.toFloat())
                    if (!lastBase.isNaN() && lastBase != next) {
                        val delta = lastBase - next
                        if (selected) {
                            onSelectedBaseShift(delta)
                        } else {
                            scope.launch {
                                settle.snapTo(settle.value + delta)
                                settle.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
                                )
                            }
                        }
                    }
                    lastBase = next
                }
                .zIndex(if (selected) 3f else 0f)
                .graphicsLayer {
                    translationY = if (selected) dragOffsetY else settle.value
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = if (selected) 12.dp.toPx() else 0f
                }
                .alpha(if (selected) 1f else 0.995f),
    ) {
        content()
    }
}

/**
 * Reorders controls only inside their own visual container. No child background, border or shell
 * is introduced here; the caller keeps drawing the original Light panel.
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
    val latestOrder by rememberUpdatedState(order)
    val latestOnOrderChange by rememberUpdatedState(onOrderChange)
    val latestOnOrderSettled by rememberUpdatedState(onOrderSettled)
    val latestOnEditStarted by rememberUpdatedState(onEditStarted)

    val bounds = remember { mutableStateMapOf<T, AxisBounds>() }
    var dragged by remember { mutableStateOf<T?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var workingOrder by remember { mutableStateOf(order) }

    LaunchedEffect(order, dragged) {
        if (dragged == null) workingOrder = order
    }

    Row(
        modifier = modifier,
        verticalAlignment = verticalAlignment,
    ) {
        order.forEach { item ->
            key(item) {
                val selected = dragged == item
                val scope = rememberCoroutineScope()
                val settle = remember(item) { Animatable(0f) }
                var lastBase by remember(item) { mutableFloatStateOf(Float.NaN) }
                val scale by
                    animateFloatAsState(
                        targetValue = if (selected) 1.035f else 1f,
                        animationSpec = spring(dampingRatio = 0.70f, stiffness = Spring.StiffnessMediumLow),
                        label = "capsuleInnerLift",
                    )

                val dragGesture =
                    if (!editable) {
                        Modifier
                    } else {
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
                                    dragX += amount.x

                                    val index = workingOrder.indexOf(item)
                                    val itemBounds = bounds[item]
                                    if (index < 0 || itemBounds == null) {
                                        return@detectDragGesturesAfterLongPress
                                    }

                                    val center = itemBounds.center + dragX
                                    var target = index

                                    if (index > 0) {
                                        val previous = workingOrder[index - 1]
                                        val previousCenter = bounds[previous]?.center
                                        if (previousCenter != null && center < previousCenter) {
                                            target = index - 1
                                        }
                                    }

                                    if (target == index && index < workingOrder.lastIndex) {
                                        val next = workingOrder[index + 1]
                                        val nextCenter = bounds[next]?.center
                                        if (nextCenter != null && center > nextCenter) {
                                            target = index + 1
                                        }
                                    }

                                    if (target != index) {
                                        val moved = workingOrder.toMutableList()
                                        moved.removeAt(index)
                                        moved.add(target, item)
                                        workingOrder = moved
                                        latestOnOrderChange(moved)
                                    }
                                },
                                onDragEnd = {
                                    val settledOrder = workingOrder
                                    dragged = null
                                    dragX = 0f
                                    latestOnOrderSettled(settledOrder)
                                },
                                onDragCancel = {
                                    dragged = null
                                    dragX = 0f
                                    latestOnOrderChange(workingOrder)
                                    latestOnOrderSettled(workingOrder)
                                },
                            )
                        }
                    }

                Box(
                    modifier =
                        Modifier
                            .weight(weightFor(item))
                            .onGloballyPositioned { coordinates ->
                                val next = coordinates.positionInParent().x
                                bounds[item] = AxisBounds(next, coordinates.size.width.toFloat())
                                if (!lastBase.isNaN() && lastBase != next) {
                                    val delta = lastBase - next
                                    if (selected) {
                                        dragX += delta
                                    } else {
                                        scope.launch {
                                            settle.snapTo(settle.value + delta)
                                            settle.animateTo(
                                                0f,
                                                spring(
                                                    dampingRatio = 0.70f,
                                                    stiffness = Spring.StiffnessLow,
                                                ),
                                            )
                                        }
                                    }
                                }
                                lastBase = next
                            }
                            .then(if (dragHandleOnly) Modifier else dragGesture)
                            .zIndex(if (selected) 4f else 0f)
                            .graphicsLayer {
                                translationX = if (selected) dragX else settle.value
                                scaleX = scale
                                scaleY = scale
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    content(item)

                    if (editable && dragHandleOnly) {
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
    editable: Boolean,
    onEditStarted: () -> Unit,
    onResizePreview: (widthScale: Float, heightScale: Float) -> Unit,
    onResizeSettled: (widthScale: Float, heightScale: Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var selected by remember { mutableStateOf(false) }
    var currentWidth by remember(widthScale) { mutableFloatStateOf(widthScale.coerceIn(0.55f, 1.08f)) }
    var currentHeight by remember(heightScale) { mutableFloatStateOf(heightScale.coerceIn(0.55f, 1.35f)) }

    LaunchedEffect(editable) {
        if (!editable) selected = false
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
        contentAlignment = Alignment.Center,
    ) {
        content()

        if (selected && editable) {
            ArtworkResizeHandle.entries.forEach { handle ->
                CapsuleArtworkResizeHandle(
                    handle = handle,
                    baseSide = baseSide,
                    widthScale = currentWidth,
                    heightScale = currentHeight,
                    onEditStarted = onEditStarted,
                    onResize = { w, h ->
                        currentWidth = w
                        currentHeight = h
                        onResizePreview(w, h)
                    },
                    onResizeSettled = {
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
    onEditStarted: () -> Unit,
    onResize: (Float, Float) -> Unit,
    onResizeSettled: () -> Unit,
) {
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
                .pointerInput(handle, baseSide, widthScale, heightScale) {
                    var w = widthScale
                    var h = heightScale
                    val basePx = baseSide.toPx().coerceAtLeast(1f)
                    detectDragGestures(
                        onDragStart = { onEditStarted() },
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
                                    h = (h + amount.y / basePx).coerceIn(0.55f, 1.35f)
                                }
                                ArtworkResizeHandle.BOTTOM_LEFT -> {
                                    w = (w - amount.x / basePx).coerceIn(0.55f, 1.08f)
                                    h = (h + amount.y / basePx).coerceIn(0.55f, 1.35f)
                                }
                                ArtworkResizeHandle.BOTTOM_RIGHT -> {
                                    w = (w + amount.x / basePx).coerceIn(0.55f, 1.08f)
                                    h = (h + amount.y / basePx).coerceIn(0.55f, 1.35f)
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
