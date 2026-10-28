package com.nikhil.yt.ui.menu

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikhil.yt.ui.component.PlaylistAction

/**
 * Playlist hero and More must use one choice/dialog controller, not two
 * independent click handlers with different stop/delete behavior.
 */
@Composable
internal fun RowScope.CapsuleCollectionDownloadButton(
    actionKey: String,
    collectionName: String,
    state: Int,
    hasStoredItems: Boolean,
    hasCompletedItems: Boolean,
    enabled: Boolean = true,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
) {
    val trayScope = this
    CapsuleDownloadAction(
        actionKey = actionKey,
        collectionName = collectionName,
        state = state,
        hasStoredItems = hasStoredItems,
        hasCompletedItems = hasCompletedItems,
        enabled = enabled,
        onDownload = onDownload,
        onStop = onStop,
        onRemove = onRemove,
        renderAction = { visual, onClick ->
            with(trayScope) {
                PlaylistAction(
                    icon = visual.iconRes,
                    label = stringResource(visual.labelRes),
                    onClick = onClick,
                    enabled = visual.enabled,
                    loading = visual.isLoading,
                )
            }
        },
    )
}
