package com.nikhil.yt.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nikhil.yt.ui.player.appIsOnScreen
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Warm only the next two shelves, at most two concurrent requests, while visible.
 * Scroll boundary changes and backgrounding cancel obsolete work. Coil owns the cache.
 */
@Composable
internal fun PrefetchFeedArtwork(
    state: LazyListState,
    shelves: List<Pair<String, List<String>>>,
) {
    val context = LocalContext.current
    val onScreen = appIsOnScreen()
    LaunchedEffect(state, shelves, onScreen) {
        if (!onScreen || shelves.isEmpty()) return@LaunchedEffect
        val slots = Semaphore(2)
        snapshotFlow {
            val visibleKeys = state.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
            val last = shelves.indexOfLast { it.first in visibleKeys }
            shelves.drop(last + 1).take(2).flatMap { it.second.take(3) }.distinct()
        }.distinctUntilChanged().collectLatest { urls ->
            coroutineScope {
                urls.forEach { url ->
                    launch {
                        slots.withPermit {
                            context.imageLoader.execute(ImageRequest.Builder(context)
                                .data(capsuleArtworkUrl(url, 512))
                                .size(512, 512).crossfade(false).build())
                        }
                    }
                }
            }
        }
    }
}
