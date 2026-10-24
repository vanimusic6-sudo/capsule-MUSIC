package com.nikhil.yt.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nikhil.yt.LocalDownloadUtil
import com.nikhil.yt.playback.collectionDownloadState

/** Recomputed from both membership and Media3's authoritative index, including index restoration. */
@Composable
internal fun rememberCollectionDownloadState(songIds: Collection<String>): Int {
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()
    return collectionDownloadState(songIds, downloads)
}
