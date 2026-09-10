package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nikhil.yt.R
import com.nikhil.yt.constants.AudioClientOrder
import kotlin.math.abs

@Composable
internal fun AudioClientPriorityDialog(
    currentOrder: List<String>,
    resetOrder: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var draftOrder by remember(currentOrder) { mutableStateOf(currentOrder) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.audio_client_priority_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.audio_client_priority_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(16.dp))

                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = draftOrder,
                        key = { _, profileId -> profileId },
                    ) { index, profileId ->
                        AudioClientPriorityRow(
                            position = index + 1,
                            profileId = profileId,
                            onMove = { direction ->
                                val from = draftOrder.indexOf(profileId)
                                if (from >= 0) {
                                    val to = (from + direction).coerceIn(draftOrder.indices)
                                    if (to != from) {
                                        draftOrder =
                                            draftOrder.toMutableList().apply {
                                                add(to, removeAt(from))
                                            }
                                    }
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.audio_client_priority_safety_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            draftOrder =
                                AudioClientOrder.resolve(
                                    raw = resetOrder.joinToString(","),
                                    legacyPolicy = com.nikhil.yt.constants.AudioStreamPolicy.VISIONOS,
                                )
                        },
                    ) {
                        Text(stringResource(R.string.audio_client_priority_reset))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.audio_client_priority_cancel))
                    }
                    TextButton(onClick = { onSave(draftOrder) }) {
                        Text(stringResource(R.string.audio_client_priority_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioClientPriorityRow(
    position: Int,
    profileId: String,
    onMove: (Int) -> Unit,
) {
    var dragDistance by remember(profileId) { mutableStateOf(0f) }
    val moveThresholdPx = with(LocalDensity.current) { 44.dp.toPx() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(30.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = audioClientTitle(profileId),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = audioClientDescription(profileId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = profileId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Text(
                text = "≡",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(start = 12.dp)
                        .width(36.dp)
                        .pointerInput(profileId, position) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragDistance = 0f },
                                onDragCancel = { dragDistance = 0f },
                                onDragEnd = { dragDistance = 0f },
                                onDrag = { _, dragAmount ->
                                    dragDistance += dragAmount.y
                                    if (abs(dragDistance) >= moveThresholdPx) {
                                        onMove(if (dragDistance > 0f) 1 else -1)
                                        dragDistance = 0f
                                    }
                                },
                            )
                        },
            )
        }
    }
}

@Composable
private fun audioClientTitle(profileId: String): String =
    when (profileId) {
        AudioClientOrder.VISIONOS -> stringResource(R.string.audio_client_visionos)
        AudioClientOrder.VISIONOS_0_1 -> stringResource(R.string.audio_client_visionos_compat)
        AudioClientOrder.WEB_REMIX -> stringResource(R.string.audio_client_web_remix)
        AudioClientOrder.WEB_EMBEDDED -> stringResource(R.string.audio_client_web_embedded)
        AudioClientOrder.WEB_CREATOR -> stringResource(R.string.audio_client_web_creator)
        AudioClientOrder.TVHTML5_SIMPLY -> stringResource(R.string.audio_client_tv)
        else -> profileId
    }

@Composable
private fun audioClientDescription(profileId: String): String =
    when (profileId) {
        AudioClientOrder.VISIONOS -> stringResource(R.string.audio_client_visionos_description)
        AudioClientOrder.VISIONOS_0_1 -> stringResource(R.string.audio_client_visionos_compat_description)
        AudioClientOrder.WEB_REMIX -> stringResource(R.string.audio_client_web_remix_description)
        AudioClientOrder.WEB_EMBEDDED -> stringResource(R.string.audio_client_web_embedded_description)
        AudioClientOrder.WEB_CREATOR -> stringResource(R.string.audio_client_web_creator_description)
        AudioClientOrder.TVHTML5_SIMPLY -> stringResource(R.string.audio_client_tv_description)
        else -> profileId
    }
