/**
 * Capsule MUSIC
 * A draggable ranking, shared by everything that has a "try these in this order" setting.
 * GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nikhil.yt.R
import com.nikhil.yt.ui.motion.capsuleDialogEntrance
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val PriorityMoveSpec = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)
private val PriorityLiftSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * Drag a list of ids into the order they should be tried in.
 *
 * Shared rather than copied because the audio clients and the lyrics providers are the same
 * question asked about different things, and the fiddly half — keeping a drag from stuttering while
 * DataStore publishes each move back — is worth having in one place and getting right once.
 */
@Composable
internal fun PriorityOrderDialog(
    title: String,
    description: String,
    note: String?,
    currentOrder: List<String>,
    onReset: () -> List<String>,
    onDismiss: () -> Unit,
    onOrderChange: (List<String>) -> Unit,
    label: @Composable (String) -> String,
    sublabel: @Composable (String) -> String,
    showRawId: Boolean = true,
) {
    // Keep the in-dialog list stable while DataStore publishes live updates back to the parent.
    // Re-keying this state from currentOrder would interrupt an active drag on every persisted move.
    var orderedIds by remember { mutableStateOf(currentOrder) }
    var pendingOrderChange by remember { mutableStateOf(false) }
    var isReordering by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    fun saveSettledOrder() {
        if (pendingOrderChange) {
            pendingOrderChange = false
            onOrderChange(orderedIds)
        }
    }
    // The drag library animates the released item back to its new slot AFTER
    // the gesture ends. Persisting immediately triggers parent/DataStore work
    // inside that settling window; recording 9467.mp4 shows that as a gap,
    // followed by one row teleporting into place (29-39s).
    // Delay the publication, NOT the local reorder. The local list updates
    // synchronously on every crossing to keep the item under the finger.
    LaunchedEffect(isReordering, pendingOrderChange) {
        if (!isReordering && pendingOrderChange) {
            delay(320)
            saveSettledOrder()
        }
    }
    val lazyListState = rememberLazyListState()
    val reorderableState =
        rememberReorderableLazyListState(lazyListState) { from, to ->
            val fromIndex = orderedIds.indexOfFirst { it == from.key }
            val toIndex = orderedIds.indexOfFirst { it == to.key }
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                val reordered =
                    orderedIds.toMutableList().apply {
                        add(toIndex, removeAt(fromIndex))
                    }
                // The library needs a synchronous, locally keyed list change to keep the
                // item under the finger. Do NOT publish to DataStore on every crossed row:
                // it feeds snapshots back mid-gesture, creating jumpy rearrangements.
                orderedIds = reordered
                pendingOrderChange = true
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

    val dismissAfterSave = {
        saveSettledOrder()
        onDismiss()
    }
    Dialog(onDismissRequest = dismissAfterSave) {
        // The previous dialog could exceed the phone's available height:
        // its 430dp list plus note and buttons pushed Done/Reset almost
        // underneath the system navigation bar (clearly visible at 29s).
        // Give the list the REMAINING height, not an independent 430dp.
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().heightIn(min = 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(max = maxHeight - 24.dp)
                    .capsuleDialogEntrance(),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .weight(1f, fill = false),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = orderedIds,
                        key = { _, id -> id },
                    ) { index, id ->
                        ReorderableItem(
                            state = reorderableState,
                            key = id,
                            animateItemModifier = Modifier.animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = PriorityMoveSpec,
                            ),
                        ) { isDragging ->
                            PriorityRow(
                                position = index + 1,
                                id = id,
                                label = label(id),
                                sublabel = sublabel(id),
                                showRawId = showRawId,
                                isDragging = isDragging,
                                dragHandleModifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        isReordering = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = {
                                        isReordering = false
                                    },
                                ),
                            )
                        }
                    }
                }

                if (note != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            val reset = onReset()
                            orderedIds = reset
                            pendingOrderChange = false
                            onOrderChange(reset)
                        },
                    ) {
                        Text(stringResource(R.string.audio_client_priority_reset))
                    }
                    TextButton(onClick = dismissAfterSave) {
                        Text(stringResource(R.string.audio_client_priority_done))
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun PriorityRow(
    position: Int,
    id: String,
    label: String,
    sublabel: String,
    showRawId: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
) {
    // The lifted item remains attached to the finger; its neighbours move
    // with one non-bouncy placement spring instead of snapping or leapfrogging.
    val lift by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "priorityRowLift",
    )
    val scale = animateFloatAsState(
        targetValue = if (isDragging) 1.016f else 1f,
        animationSpec = PriorityLiftSpec,
        label = "priorityRowPressedScale",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = lift,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 104.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(30.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showRawId) {
                    Text(
                        text = id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(48.dp)
                    .then(dragHandleModifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "≡",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
