package com.nikhil.yt.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikhil.yt.R

/** Shared by the Super control row and the Light overflow menu. */
@Composable
internal fun CapsuleSleepTimerDialog(
    minutes: Float,
    enabled: Boolean,
    active: Boolean,
    onMinutesChange: (Float) -> Unit,
    onEndOfSong: () -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column {
                TextButton(onClick = onEndOfSong, enabled = enabled) {
                    Text(stringResource(R.string.end_of_song))
                }
                Text(stringResource(R.string.capsule_timer_minutes, minutes.toInt()))
                Slider(minutes, onMinutesChange, enabled = enabled, valueRange = 5f..120f, steps = 22)
                if (active) {
                    TextButton(onClick = onClear, enabled = enabled) {
                        Text(stringResource(R.string.capsule_timer_turn_off))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) { Text(stringResource(R.string.ok_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}
