/*
 * Velune - by Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.utils.CapsuleScrollbarMath
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Fast scrollbar with a single conflated scroll consumer.
 *
 * The previous implementation started one coroutine per drag sample, wrote
 * snapshot state inside derivedStateOf, and mapped the thumb to a changing
 * partially-adjusted item range. Queued scrollToItem jobs could run out of
 * order and fling the library to unrelated positions after a finger stopped.
 *
 * Only this 24dp rail consumes pointer input. Normal content scrolling remains
 * entirely inside Compose's native lazy list gesture recognizer.
 */
@Composable
fun DraggableScrollbar(
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
    thumbColor: Color = LocalContentColor.current.copy(alpha = 0.8f),
    thumbColorActive: Color = MaterialTheme.colorScheme.secondary,
    thumbHeight: Dp = 72.dp,
    thumbWidth: Dp = 8.dp,
    thumbCornerRadius: Dp = 4.dp,
    trackWidth: Dp = 24.dp,
    minItemCountForScroll: Int = 15,
    minScrollRangeForDrag: Int = 5,
    headerItems: Int = 0,
) {
    val density = LocalDensity.current
    val thumbPx = with(density) { thumbHeight.toPx() }
    val moves = remember(scrollState) { Channel<Int>(Channel.CONFLATED) }

    // One collector, no launch-per-pixel, no race between several scrollToItem jobs.
    LaunchedEffect(scrollState, moves) {
        moves.receiveAsFlow().collectLatest { index ->
            scrollState.scrollToItem(index)
        }
    }

    val scrollable by remember(scrollState, headerItems, minItemCountForScroll, minScrollRangeForDrag) {
        derivedStateOf {
            val info = scrollState.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo.size
            val maxStart = CapsuleScrollbarMath.lastStartingIndex(total, visible)
            total - headerItems > minItemCountForScroll &&
                maxStart > minScrollRangeForDrag
        }
    }
    if (!scrollable) return

    var dragging by remember(scrollState) { mutableStateOf(false) }
    var dragFraction by remember(scrollState) { mutableFloatStateOf(0f) }
    var lastRequestedIndex by remember(scrollState) { mutableIntStateOf(-1) }

    BoxWithConstraints(
        modifier = modifier.width(trackWidth).fillMaxHeight(),
    ) {
        val viewportPx = with(density) { maxHeight.toPx() }
        val firstVisible = scrollState.layoutInfo.visibleItemsInfo.firstOrNull()
        val maxIndex = CapsuleScrollbarMath.lastStartingIndex(
            scrollState.layoutInfo.totalItemsCount,
            scrollState.layoutInfo.visibleItemsInfo.size,
        )
        val nativeFraction = CapsuleScrollbarMath.fractionForIndex(
            scrollState.firstVisibleItemIndex,
            scrollState.firstVisibleItemScrollOffset,
            firstVisible?.size ?: 0,
            maxIndex,
        )
        val shownFraction = if (dragging) dragFraction else nativeFraction
        val y = CapsuleScrollbarMath.thumbTop(shownFraction, viewportPx, thumbPx)

        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(thumbWidth)
                .align(Alignment.CenterEnd)
                .pointerInput(scrollState, viewportPx, thumbPx) {
                    detectDragGestures(
                        onDragStart = { point ->
                            dragging = true
                            lastRequestedIndex = -1
                            dragFraction = CapsuleScrollbarMath.fractionForTouch(
                                point.y, viewportPx, thumbPx,
                            )
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = CapsuleScrollbarMath.fractionForTouch(
                            change.position.y, viewportPx, thumbPx,
                        )
                        val info = scrollState.layoutInfo
                        val lastIndex = CapsuleScrollbarMath.lastStartingIndex(
                            info.totalItemsCount, info.visibleItemsInfo.size,
                        )
                        val index = CapsuleScrollbarMath.indexForFraction(
                            dragFraction, lastIndex,
                        )
                        if (index != lastRequestedIndex) {
                            lastRequestedIndex = index
                            moves.trySend(index)
                        }
                    }
                },
        ) {
            drawRoundRect(
                color = if (dragging) thumbColorActive else thumbColor,
                topLeft = Offset(0f, y),
                size = Size(size.width, thumbPx.coerceAtMost(size.height)),
                cornerRadius = CornerRadius(thumbCornerRadius.toPx()),
            )
        }
    }
}
