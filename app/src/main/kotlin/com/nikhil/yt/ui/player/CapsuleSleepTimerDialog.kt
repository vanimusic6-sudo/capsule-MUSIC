package com.nikhil.yt.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                Text(stringResource(R.string.capsule_timer_minutes, minutes.toInt()))
                Slider(minutes, onMinutesChange, enabled = enabled, valueRange = 5f..120f, steps = 22)
                if (active) {
                    Button(onClick = onClear, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.capsule_timer_turn_off))
                    }
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onEndOfSong, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(stringResource(R.string.end_of_song))
                }
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(stringResource(R.string.cancel_button))
                }
                TextButton(onClick = onConfirm, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(stringResource(R.string.ok_button))
                }
            }
        },
    )
}
