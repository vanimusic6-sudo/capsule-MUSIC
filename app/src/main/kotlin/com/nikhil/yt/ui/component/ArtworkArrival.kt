package com.nikhil.yt.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** One finite reveal per visible source, including a memory-cache hit.
 * Read the returned state only in a draw/layer lambda. A late loading/error
 * callback cannot hide pixels that have already arrived for this identity.
 */
@Composable
internal fun rememberArtworkArrivalAlpha(identity: Any?, decoded: Boolean): State<Float> {
    val alpha = remember(identity) { Animatable(0f) }
    val decodedNow = rememberUpdatedState(decoded)
    LaunchedEffect(alpha) {
        snapshotFlow { decodedNow.value }.first { it }
        alpha.animateTo(1f, tween(180))
    }
    return alpha.asState()
}
