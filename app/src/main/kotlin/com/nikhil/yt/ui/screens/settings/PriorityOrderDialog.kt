/**
 * Capsule MUSIC
 * A draggable ranking, shared by everything that has a "try these in this order" setting.
 * GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nikhil.yt.R
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
                // The reorder library requires the backing list to change synchronously.
                // Persist only after that local move so the dragged row never flickers or jumps.
                orderedIds = reordered
                onOrderChange(reordered)
            }
        }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = orderedIds,
                        key = { _, id -> id },
                    ) { index, id ->
                        ReorderableItem(state = reorderableState, key = id) { isDragging ->
                            PriorityRow(
                                position = index + 1,
                                id = id,
                                label = label(id),
                                sublabel = sublabel(id),
                                showRawId = showRawId,
                                isDragging = isDragging,
                                dragHandleModifier = Modifier.draggableHandle(),
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
                            onOrderChange(reset)
                        },
                    ) {
                        Text(stringResource(R.string.audio_client_priority_reset))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.audio_client_priority_done))
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = if (isDragging) 8.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(30.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (showRawId) {
                    Text(
                        text = id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Text(
                text = "≡",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(start = 12.dp)
                        .width(36.dp)
                        .then(dragHandleModifier),
            )
        }
    }
}
