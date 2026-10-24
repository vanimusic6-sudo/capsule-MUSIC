package com.nikhil.yt.ui.menu

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikhil.yt.R

/** Destructive removal is explicit; dismissing the dialog retains all bytes. */
@Composable
fun DownloadRemovalDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capsule_stop_and_delete_download)) },
        text = { Text(stringResource(R.string.capsule_stop_and_delete_download_message)) },
        confirmButton = { TextButton(onClick = onConfirm) {
            Text(stringResource(R.string.capsule_stop_and_delete_download))
        } },
        dismissButton = { TextButton(onClick = onDismiss) {
            Text(stringResource(android.R.string.cancel))
        } },
    )
}
